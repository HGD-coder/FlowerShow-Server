package com.github.hgdcoder.flowershow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flower-show.recommendation.embedding")
public record EmbeddingProperties(
        boolean enabled,
        String baseUrl,
        String model,
        int dimensions,
        int batchSize,
        int candidateLimit,
        double minimumSimilarity,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration initialDelay,
        Duration refreshInterval
) {
    public EmbeddingProperties {
        baseUrl = requiredText(baseUrl, "base-url");
        model = requiredText(model, "model");
        requirePositive(dimensions, "dimensions");
        requirePositive(batchSize, "batch-size");
        requirePositive(candidateLimit, "candidate-limit");
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        requireNonNegative(initialDelay, "initial-delay");
        requirePositive(refreshInterval, "refresh-interval");
        if (!Double.isFinite(minimumSimilarity)
                || minimumSimilarity < -1.0
                || minimumSimilarity >= 1.0) {
            throw new IllegalArgumentException(
                    "flower-show.recommendation.embedding.minimum-similarity must be in [-1, 1)."
            );
        }
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "flower-show.recommendation.embedding." + name + " must not be blank."
            );
        }
        return value.trim();
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "flower-show.recommendation.embedding." + name + " must be positive."
            );
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() == 0) {
            throw new IllegalArgumentException(
                    "flower-show.recommendation.embedding." + name
                            + " must be at least one millisecond."
            );
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(
                    "flower-show.recommendation.embedding." + name + " must not be negative."
            );
        }
    }
}
