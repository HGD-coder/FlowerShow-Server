package com.github.hgdcoder.flowershow.chat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.config.ChatWebSocketProperties;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

final class ChatWebSocketHandshakeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final int TOKEN_EXPIRED_CLOSE_CODE = 4001;

    private final ChatWebSocketProperties properties;
    private final JwtDecoder jwtDecoder;
    private final ChatChannelRegistry channelRegistry;
    private final ObjectMapper objectMapper;

    ChatWebSocketHandshakeHandler(
            ChatWebSocketProperties properties,
            JwtDecoder jwtDecoder,
            ChatChannelRegistry channelRegistry,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.jwtDecoder = jwtDecoder;
        this.channelRegistry = channelRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            reject(context, HttpResponseStatus.BAD_REQUEST, false);
            return;
        }
        String requestPath;
        try {
            requestPath = new QueryStringDecoder(request.uri()).path();
        } catch (IllegalArgumentException e) {
            reject(context, HttpResponseStatus.BAD_REQUEST, false);
            return;
        }
        if (!properties.path().equals(requestPath)) {
            reject(context, HttpResponseStatus.NOT_FOUND, false);
            return;
        }
        if (!HttpMethod.GET.equals(request.method())) {
            reject(context, HttpResponseStatus.METHOD_NOT_ALLOWED, false);
            return;
        }

        String token = bearerToken(request.headers().get(HttpHeaderNames.AUTHORIZATION));
        if (token == null) {
            reject(context, HttpResponseStatus.UNAUTHORIZED, true);
            return;
        }

        String userId;
        Instant tokenExpiresAt;
        try {
            Jwt jwt = jwtDecoder.decode(token);
            userId = jwt.getSubject();
            tokenExpiresAt = jwt.getExpiresAt();
            if (userId == null || userId.isBlank() || tokenExpiresAt == null) {
                reject(context, HttpResponseStatus.UNAUTHORIZED, true);
                return;
            }
        } catch (Exception e) {
            reject(context, HttpResponseStatus.UNAUTHORIZED, true);
            return;
        }

        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                webSocketLocation(request),
                null,
                false,
                properties.maxFramePayloadLength()
        );
        WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(context.channel());
            return;
        }

        context.pipeline().addAfter(
                context.name(),
                "chatWebSocketFrames",
                new ChatWebSocketFrameHandler(objectMapper)
        );
        ChannelFuture handshake = handshaker.handshake(context.channel(), request);
        handshake.addListener(future -> {
            if (!future.isSuccess()) {
                context.close();
                return;
            }
            context.pipeline().remove(this);
            channelRegistry.register(userId, context.channel());
            scheduleTokenExpiry(context, tokenExpiresAt);
            context.channel().writeAndFlush(new TextWebSocketFrame("{\"type\":\"connection.ready\"}"));
        });
    }

    private static void scheduleTokenExpiry(
            ChannelHandlerContext context,
            Instant tokenExpiresAt
    ) {
        long delayMillis = Math.max(
                1,
                Duration.between(Instant.now(), tokenExpiresAt).toMillis()
        );
        ScheduledFuture<?> expiryTask = context.executor().schedule(
                () -> {
                    if (context.channel().isActive()) {
                        context.writeAndFlush(new CloseWebSocketFrame(
                                        TOKEN_EXPIRED_CLOSE_CODE,
                                        "Access token expired."
                                ))
                                .addListener(ChannelFutureListener.CLOSE);
                    }
                },
                delayMillis,
                TimeUnit.MILLISECONDS
        );
        context.channel().closeFuture().addListener(ignored -> expiryTask.cancel(false));
    }

    private String webSocketLocation(FullHttpRequest request) {
        String forwardedProto = request.headers().get("X-Forwarded-Proto");
        String scheme = "https".equalsIgnoreCase(forwardedProto) ? "wss" : "ws";
        String host = request.headers().get(HttpHeaderNames.HOST, "localhost");
        return scheme + "://" + host + properties.path();
    }

    private static String bearerToken(String authorization) {
        if (authorization == null
                || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() || token.indexOf(' ') >= 0 ? null : token;
    }

    private static void reject(
            ChannelHandlerContext context,
            HttpResponseStatus status,
            boolean bearerChallenge
    ) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status
        );
        HttpUtil.setContentLength(response, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        if (bearerChallenge) {
            response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer");
        }
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
