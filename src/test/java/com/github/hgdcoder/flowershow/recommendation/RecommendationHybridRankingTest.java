package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Actor;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.SearchPageData;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository.VideoCandidate;
import com.github.hgdcoder.flowershow.recommendation.embedding.SemanticScoreProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationHybridRankingTest {

    @Test
    void includesSemanticOnlyCandidateWhileKeepingExactKeywordResultFirst() {
        RecommendationRepository repository = mock(RecommendationRepository.class);
        RecommendationTokenService tokens = mock(RecommendationTokenService.class);
        SemanticScoreProvider semantic = mock(SemanticScoreProvider.class);
        VideoCandidate exact = candidate("exact", "Rose garden", List.of("rose"));
        VideoCandidate semanticOnly = candidate("semantic", "Evening romance", List.of("date night"));

        when(repository.interests("actor")).thenReturn(Map.of());
        when(repository.eligibleVideos("viewer")).thenReturn(List.of(exact));
        when(repository.eligibleVideosByIds(eq("viewer"), anyCollection()))
                .thenReturn(List.of(exact, semanticOnly));
        when(semantic.minimumSimilarity()).thenReturn(0.35);
        when(semantic.searchScores("rose")).thenReturn(Map.of(
                exact.payload().id(), 0.40,
                semanticOnly.payload().id(), 0.95
        ));
        when(tokens.signExposure(any())).thenReturn("exposure");

        RecommendationService service = new RecommendationService(
                repository,
                tokens,
                semantic,
                new ObjectMapper(),
                Duration.ofMinutes(30),
                Duration.ofHours(24)
        );

        SearchPageData result = service.search(
                new Actor("actor", "viewer"),
                "rose",
                null,
                20
        );

        assertEquals(2, result.items().size());
        assertEquals("exact", result.items().get(0).id());
        assertEquals("hybrid_search", result.items().get(0).source());
        assertEquals("semantic", result.items().get(1).id());
        assertEquals("semantic_search", result.items().get(1).source());
    }

    private static VideoCandidate candidate(String id, String title, List<String> terms) {
        VideoCardDto payload = new VideoCardDto(
                "video",
                id,
                title,
                "author",
                null,
                "https://example.test/" + id + ".mp4",
                null,
                null,
                0,
                0,
                0,
                0,
                terms,
                terms,
                "author-id",
                null,
                null,
                null,
                1,
                null,
                null,
                "author-id",
                false,
                false
        );
        return new VideoCandidate(
                payload,
                "author-id",
                1,
                0,
                0,
                0,
                0,
                terms
        );
    }
}
