package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Actor;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Snapshot;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository.VideoCandidate;
import com.github.hgdcoder.flowershow.recommendation.embedding.SemanticScoreProvider;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationServiceFeedRankingTest {

    @Test
    void fixedSeedReproducesTheCompleteSnapshotOrderWithoutDuplicates() {
        RecommendationService service = serviceWith(closeCandidates());
        Actor actor = new Actor("ranking-actor", null);

        Snapshot first = service.feedSnapshot(actor, "fixed-session-seed");
        Snapshot replay = service.feedSnapshot(actor, "fixed-session-seed");
        List<String> firstIds = ids(first);

        assertEquals(firstIds, ids(replay));
        assertEquals(firstIds.size(), new HashSet<>(firstIds).size());
    }

    @Test
    void differentSeedsReorderCloseCandidates() {
        RecommendationService service = serviceWith(closeCandidates());
        Actor actor = new Actor("ranking-actor", null);

        List<String> first = ids(service.feedSnapshot(actor, "session-seed-one"));
        List<String> second = ids(service.feedSnapshot(actor, "session-seed-two"));

        assertNotEquals(first, second);
    }

    @Test
    void sessionRandomnessCannotPromoteCandidatesOutsideTheRelevanceWindow() {
        RecommendationService service = serviceWith(List.of(
                candidate("clear-best", "author-best", 1_000_000, 100_000, 50_000, 25_000),
                candidate("distant-one", "author-one", 0, 0, 0, 0),
                candidate("distant-two", "author-two", 0, 0, 0, 0)
        ));
        Actor actor = new Actor("ranking-actor", null);

        for (String seed : List.of("boundary-one", "boundary-two", "boundary-three")) {
            assertEquals("clear-best", ids(service.feedSnapshot(actor, seed)).get(0));
        }
    }

    private static RecommendationService serviceWith(List<VideoCandidate> candidates) {
        RecommendationRepository repository = mock(RecommendationRepository.class);
        RecommendationTokenService tokens = mock(RecommendationTokenService.class);
        SemanticScoreProvider semanticScores = mock(SemanticScoreProvider.class);
        when(repository.eligibleVideos(null)).thenReturn(candidates);
        when(repository.interests("ranking-actor")).thenReturn(Map.of());
        when(semanticScores.feedScores("ranking-actor")).thenReturn(Map.of());
        return new RecommendationService(
                repository,
                tokens,
                semanticScores,
                new ObjectMapper(),
                Duration.ofMinutes(30),
                Duration.ofHours(24)
        );
    }

    private static List<VideoCandidate> closeCandidates() {
        return IntStream.range(0, 10)
                .mapToObj(index -> candidate("close-" + index, "author-" + index))
                .toList();
    }

    private static VideoCandidate candidate(String id, String authorId) {
        return candidate(id, authorId, 100, 10, 2, 1);
    }

    private static VideoCandidate candidate(
            String id,
            String authorId,
            long views,
            long likes,
            long favorites,
            long shares
    ) {
        VideoCardDto payload = new VideoCardDto(
                "video",
                id,
                "Close candidate " + id,
                authorId,
                null,
                "https://example.test/" + id + ".mp4",
                "https://example.test/" + id + ".jpg",
                null,
                10,
                1,
                2,
                1,
                List.of("flower"),
                List.of(),
                authorId,
                authorId,
                null,
                null,
                1_700_000_000L,
                null,
                null,
                authorId,
                false,
                false
        );
        return new VideoCandidate(
                payload,
                authorId,
                payload.publishTime(),
                views,
                likes,
                favorites,
                shares,
                payload.tags()
        );
    }

    private static List<String> ids(Snapshot snapshot) {
        return snapshot.items().stream().map(item -> item.entityId()).toList();
    }
}
