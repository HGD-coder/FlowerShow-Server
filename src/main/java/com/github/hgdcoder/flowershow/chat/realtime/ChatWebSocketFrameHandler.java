package com.github.hgdcoder.flowershow.chat.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

final class ChatWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final int MAX_ACK_EVENT_ID_LENGTH = 128;

    private final ObjectMapper objectMapper;

    ChatWebSocketFrameHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        if (frame instanceof PingWebSocketFrame) {
            context.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof PongWebSocketFrame) {
            return;
        }
        if (frame instanceof CloseWebSocketFrame closeFrame) {
            context.writeAndFlush(closeFrame.retain()).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (frame instanceof TextWebSocketFrame textFrame) {
            if (!textFrame.isFinalFragment()) {
                close(context, WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "Fragmented text is not supported.");
                return;
            }
            acceptAck(context, textFrame.text());
            return;
        }
        if (frame instanceof BinaryWebSocketFrame || frame instanceof ContinuationWebSocketFrame) {
            close(context, WebSocketCloseStatus.INVALID_MESSAGE_TYPE, "Only text ACK frames are supported.");
            return;
        }
        close(context, WebSocketCloseStatus.INVALID_MESSAGE_TYPE, "Unsupported WebSocket frame.");
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.READER_IDLE) {
                close(context, WebSocketCloseStatus.ENDPOINT_UNAVAILABLE, "Idle timeout.");
                return;
            }
            if (idleEvent.state() == IdleState.WRITER_IDLE) {
                context.writeAndFlush(new PingWebSocketFrame(Unpooled.EMPTY_BUFFER));
                return;
            }
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        context.close();
    }

    private void acceptAck(ChannelHandlerContext context, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            JsonNode type = root == null ? null : root.get("type");
            JsonNode eventId = root == null ? null : root.get("eventId");
            if (root == null
                    || !root.isObject()
                    || type == null
                    || !"ack".equals(type.textValue())
                    || eventId == null
                    || !eventId.isTextual()
                    || eventId.textValue().isBlank()
                    || eventId.textValue().length() > MAX_ACK_EVENT_ID_LENGTH) {
                close(context, WebSocketCloseStatus.POLICY_VIOLATION, "Invalid ACK frame.");
            }
        } catch (Exception e) {
            close(context, WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "Invalid JSON frame.");
        }
    }

    private static void close(
            ChannelHandlerContext context,
            WebSocketCloseStatus status,
            String reason
    ) {
        context.writeAndFlush(new CloseWebSocketFrame(status.code(), reason))
                .addListener(ChannelFutureListener.CLOSE);
    }
}
