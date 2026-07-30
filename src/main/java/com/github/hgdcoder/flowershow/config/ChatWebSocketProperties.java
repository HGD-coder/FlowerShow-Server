package com.github.hgdcoder.flowershow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flower-show.chat.websocket")
public record ChatWebSocketProperties(
        boolean enabled,
        int port,
        String path,
        Duration idleTimeout,
        int maxFramePayloadLength
) {

    public ChatWebSocketProperties {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Chat WebSocket port must be between 0 and 65535.");
        }
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Chat WebSocket path must start with '/'.");
        }
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("Chat WebSocket idle timeout must be positive.");
        }
        if (maxFramePayloadLength < 128 || maxFramePayloadLength > 65_536) {
            throw new IllegalArgumentException(
                    "Chat WebSocket max frame payload length must be between 128 and 65536 bytes."
            );
        }
    }
}
