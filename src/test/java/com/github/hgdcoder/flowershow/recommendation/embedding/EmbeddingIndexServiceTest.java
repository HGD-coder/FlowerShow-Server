package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.github.hgdcoder.flowershow.config.EmbeddingProperties;
import com.github.hgdcoder.flowershow.recommendation.RecommendationActorResolver;
import com.github.hgdcoder.flowershow.recommendation.embedding.EmbeddingRepository.EmbeddingSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingIndexServiceTest {

    @Test
    void skipsUnchangedSourcesAndEmbedsOnlyChangedDocuments() {
        EmbeddingRepository repository = mock(EmbeddingRepository.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        EmbeddingProperties properties = properties();
        EmbeddingSource unchanged = new EmbeddingSource("content-1", "unchanged document");
        EmbeddingSource changed = new EmbeddingSource("content-2", "changed document");

        when(repository.contentSources()).thenReturn(List.of(unchanged, changed));
        when(repository.contentHashes(properties.model())).thenReturn(Map.of(
                unchanged.id(),
                RecommendationActorResolver.sha256Hex(unchanged.text())
        ));
        when(repository.suggestionSources()).thenReturn(List.of());
        when(repository.suggestionHashes(properties.model())).thenReturn(Map.of());
        when(repository.userInterestSources()).thenReturn(List.of());
        when(repository.userInterestHashes(properties.model())).thenReturn(Map.of());
        when(client.embed(List.of(changed.text()))).thenReturn(List.of(new float[]{0.6f, 0.8f}));

        EmbeddingIndexService.IndexResult result =
                new EmbeddingIndexService(repository, client, properties).indexOnce();

        assertEquals(1, result.contents());
        assertEquals(1, result.total());
        verify(client).embed(List.of(changed.text()));
        verify(repository).upsertContent(
                eq(changed.id()),
                eq(properties.model()),
                eq(RecommendationActorResolver.sha256Hex(changed.text())),
                eq(properties.dimensions()),
                any(float[].class)
        );
        verify(repository, never()).upsertContent(
                eq(unchanged.id()),
                any(),
                any(),
                eq(properties.dimensions()),
                any(float[].class)
        );
        verify(repository).deleteMissingContentEmbeddings(
                properties.model(),
                List.of(unchanged.id(), changed.id())
        );
        verify(repository).deleteMissingSuggestionEmbeddings(properties.model(), List.of());
        verify(repository).deleteMissingUserInterestEmbeddings(properties.model(), List.of());
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
