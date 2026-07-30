package com.github.hgdcoder.flowershow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flower-show.push")
public record PushProperties(
        boolean enabled,
        String projectId,
        int batchSize,
        Duration scheduleInterval,
        int maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff,
        Duration claimTimeout,
        Duration messageMaxAge,
        String androidChannelId,
        String notificationTitle
) {
    public PushProperties {
        projectId = optionalText(projectId);
        androidChannelId = requiredText(androidChannelId, "android-channel-id");
        notificationTitle = requiredText(notificationTitle, "notification-title");
        requirePositive(batchSize, "batch-size");
        requirePositive(maxAttempts, "max-attempts");
        requirePositive(scheduleInterval, "schedule-interval");
        requirePositive(baseBackoff, "base-backoff");
        requirePositive(maxBackoff, "max-backoff");
        requirePositive(claimTimeout, "claim-timeout");
        requirePositive(messageMaxAge, "message-max-age");
        if (baseBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException(
                    "flower-show.push.base-backoff must not exceed max-backoff."
            );
        }
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("flower-show.push." + name + " must not be blank.");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("flower-show.push." + name + " must be positive.");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() == 0) {
            throw new IllegalArgumentException(
                    "flower-show.push." + name + " must be at least one millisecond."
            );
        }
    }
}
