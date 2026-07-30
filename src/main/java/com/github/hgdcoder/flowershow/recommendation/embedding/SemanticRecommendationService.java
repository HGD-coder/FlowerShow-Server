package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.github.hgdcoder.flowershow.config.EmbeddingProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SemanticRecommendationService implements SemanticScoreProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticRecommendationService.class);
    private static final long WARNING_INTERVAL_MS = 60_000;
    private static final String SEARCH_INSTRUCTION =
            "Instruct: Given a FlowerShow video search query, retrieve semantically relevant short videos."
                    + "\nQuery: ";

    private final EmbeddingRepository repository;
    private final EmbeddingProperties properties;
    private final ObjectProvider<EmbeddingClient> clientProvider;
    private final AtomicLong lastWarningAt = new AtomicLong();

    public SemanticRecommendationService(
            EmbeddingRepository repository,
            EmbeddingProperties properties,
            ObjectProvider<EmbeddingClient> clientProvider
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clientProvider = clientProvider;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Double> searchScores(String normalizedQuery) {
        if (!available()) {
            return Map.of();
        }
        return safely(() -> {
            EmbeddingClient client = clientProvider.getIfAvailable();
            if (client == null) {
                return Map.of();
            }
            float[] query = client.embed(List.of(SEARCH_INSTRUCTION + normalizedQuery)).get(0);
            return repository.searchContentScores(
                    query,
                    properties.model(),
                    properties.dimensions(),
                    properties.candidateLimit()
            );
        });
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Double> feedScores(String actorKey) {
        if (!available()) {
            return Map.of();
        }
        return safely(() -> repository.personalizedContentScores(
                    actorKey,
                    properties.model(),
                    properties.dimensions(),
                    properties.candidateLimit()
                )
        );
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Double> suggestionScores(String actorKey) {
        if (!available()) {
            return Map.of();
        }
        return safely(() -> repository.personalizedSuggestionScores(
                    actorKey,
                    properties.model(),
                    properties.dimensions(),
                    properties.candidateLimit()
                )
        );
    }

    @Override
    public double minimumSimilarity() {
        return properties.minimumSimilarity();
    }

    private boolean available() {
        return properties.enabled() && repository.supportsVectorSearch();
    }

    private Map<String, Double> safely(Supplier<Map<String, Double>> supplier) {
        try {
            Map<String, Double> values = supplier.get();
            return values == null ? Map.of() : values;
        } catch (RuntimeException error) {
            warnOnce(error);
            return Map.of();
        }
    }

    private void warnOnce(RuntimeException error) {
        long now = System.currentTimeMillis();
        long previous = lastWarningAt.get();
        if (now - previous >= WARNING_INTERVAL_MS && lastWarningAt.compareAndSet(previous, now)) {
            LOGGER.warn(
                    "Semantic scoring failed ({}); database ranking remains active.",
                    error.getClass().getSimpleName()
            );
        }
    }
}
