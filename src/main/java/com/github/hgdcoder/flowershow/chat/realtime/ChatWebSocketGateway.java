package com.github.hgdcoder.flowershow.chat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.config.ChatWebSocketProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(ChatWebSocketProperties.class)
public class ChatWebSocketGateway implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketGateway.class);
    private static final int MAX_HANDSHAKE_REQUEST_LENGTH = 16 * 1024;

    private final ChatWebSocketProperties properties;
    private final JwtDecoder jwtDecoder;
    private final ChatChannelRegistry channelRegistry;
    private final ObjectMapper objectMapper;

    private volatile boolean running;
    private volatile int localPort = -1;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ChatWebSocketGateway(
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
    public synchronized void start() {
        if (!properties.enabled() || running) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("chat-ws-boss"));
        workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("chat-ws-worker"));
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            long idleMillis = properties.idleTimeout().toMillis();
                            long pingMillis = Math.max(1, idleMillis / 2);
                            channel.pipeline()
                                    .addLast("httpCodec", new HttpServerCodec())
                                    .addLast(
                                            "httpAggregator",
                                            new HttpObjectAggregator(MAX_HANDSHAKE_REQUEST_LENGTH)
                                    )
                                    .addLast(
                                            "chatIdle",
                                            new IdleStateHandler(
                                                    idleMillis,
                                                    pingMillis,
                                                    0,
                                                    TimeUnit.MILLISECONDS
                                            )
                                    )
                                    .addLast(
                                            "chatWebSocketHandshake",
                                            new ChatWebSocketHandshakeHandler(
                                                    properties,
                                                    jwtDecoder,
                                                    channelRegistry,
                                                    objectMapper
                                            )
                                    );
                        }
                    });
            serverChannel = bootstrap.bind(properties.host(), properties.port())
                    .syncUninterruptibly()
                    .channel();
            localPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            running = true;
            log.info(
                    "Chat WebSocket gateway listening on ws://{}:{}{}",
                    properties.host(),
                    localPort,
                    properties.path()
            );
        } catch (RuntimeException e) {
            shutdownEventLoops();
            throw new IllegalStateException("Unable to start the chat WebSocket gateway.", e);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        localPort = -1;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        shutdownEventLoops();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.enabled();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public int localPort() {
        return localPort;
    }

    private void shutdownEventLoops() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
    }
}
