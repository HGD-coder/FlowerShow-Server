package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.github.hgdcoder.flowershow.config.EmbeddingProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticRecommendationServiceTest {

    @Test
    void fallsBackForFeedAndSuggestionsWhenVectorQueriesFail() {
        EmbeddingRepository repository = mock(EmbeddingRepository.class);
        when(repository.supportsVectorSearch()).thenReturn(true);
        when(repository.personalizedContentScores("actor", "test-model", 2, 20))
                .thenThrow(new IllegalStateException("vector unavailable"));
        when(repository.personalizedSuggestionScores("actor", "test-model", 2, 20))
                .thenThrow(new IllegalStateException("vector unavailable"));

        SemanticRecommendationService service = new SemanticRecommendationService(
                repository,
                properties(),
                new StaticListableBeanFactory().getBeanProvider(EmbeddingClient.class)
        );

        assertEquals(Map.of(), service.feedScores("actor"));
        assertEquals(Map.of(), service.suggestionScores("actor"));
    }

    private static EmbeddingProperties properties() {
        return new EmbeddingProperties(
                true,
                "http://localhost:11434",
                "test-model",
                2,
                2,
                20,
                0.35,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ZERO,
                Duration.ofMinutes(10)
        );
    }
}
