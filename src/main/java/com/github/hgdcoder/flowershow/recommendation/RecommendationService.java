package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Actor;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ApiEnvelope;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.FeedPageData;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.GuessPageData;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.RankedCardItem;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.RankedSuggestion;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.SearchPageData;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ServeSession;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Snapshot;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.SnapshotItem;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.StoredHttpResponse;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository.StoredRequest;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository.SuggestionCandidate;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository.VideoCandidate;
import com.github.hgdcoder.flowershow.recommendation.embedding.SemanticScoreProvider;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.CursorClaims;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.ExposureClaims;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.TokenValidationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {

    public static final String FEED_KIND = "feed";
    public static final String GUESS_KIND = "guess";
    public static final String SEARCH_KIND = "search";
    public static final String POLICY_VERSION = "organic-only-v1";
    public static final String ALGO_VERSION = "organic-hybrid-v3";

    private final RecommendationRepository repository;
    private final RecommendationTokenService tokens;
    private final SemanticScoreProvider semanticScores;
    private final ObjectMapper objectMapper;
    private final Duration sessionTtl;
    private final Duration idempotencyTtl;

    public RecommendationService(
            RecommendationRepository repository,
            RecommendationTokenService tokens,
            SemanticScoreProvider semanticScores,
            ObjectMapper objectMapper,
            @Value("${flower-show.recommendation.session-ttl:30m}") Duration sessionTtl,
            @Value("${flower-show.recommendation.idempotency-ttl:24h}") Duration idempotencyTtl
    ) {
        this.repository = repository;
        this.tokens = tokens;
        this.semanticScores = semanticScores;
        this.objectMapper = objectMapper;
        this.sessionTtl = sessionTtl;
        this.idempotencyTtl = idempotencyTtl;
    }

    @Transactional
    public StoredHttpResponse respondIdempotently(
            Actor actor,
            String endpoint,
            String clientRequestId,
            Object request,
            Supplier<?> producer
    ) {
        String requestHash = RecommendationActorResolver.sha256Hex(writeJson(request));
        long now = System.currentTimeMillis();
        Optional<StoredRequest> existing = repository.findClientRequest(actor.actorKey(), endpoint, clientRequestId);
        if (existing.isPresent() && existing.get().expiresAtMs() > now) {
            return replay(existing.get(), requestHash);
        }
        repository.deleteExpiredClientRequest(actor.actorKey(), endpoint, clientRequestId, now);
        int claimed = repository.claimClientRequest(
                actor.actorKey(),
                endpoint,
                clientRequestId,
                requestHash,
                now + idempotencyTtl.toMillis()
        );
        if (claimed == 0) {
            StoredRequest raced = repository.findClientRequest(actor.actorKey(), endpoint, clientRequestId)
                    .orElseThrow(() -> conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS",
                            "The client request is currently being processed."));
            return replay(raced, requestHash);
        }

        Object data = producer.get();
        ApiEnvelope<Object> envelope = new ApiEnvelope<>(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", ""),
                System.currentTimeMillis(),
                data,
                null
        );
        String json = writeJson(envelope);
        repository.completeClientRequest(actor.actorKey(), endpoint, clientRequestId, 200, json);
        return new StoredHttpResponse(200, json);
    }

    @Transactional
    public FeedPageData feed(
            Actor actor,
            String requestedSessionId,
            String cursor,
            int limit,
            String scene,
            boolean refresh
    ) {
        ResolvedSession resolved = resolveSession(
                actor,
                FEED_KIND,
                requestedSessionId,
                cursor,
                null,
                refresh,
                () -> feedSnapshot(actor)
        );
        PageSlice slice = slice(resolved, limit);
        Map<String, VideoCandidate> videos = videosByIds(actor.viewerUserId(), slice.items());
        List<RankedCardItem> items = cardItems(actor, resolved.session(), slice, videos);
        return new FeedPageData(
                resolved.session().id(),
                items,
                nextCursor(resolved.session(), slice),
                slice.hasMore(),
                serveMode(scene),
                ALGO_VERSION,
                POLICY_VERSION,
                resolved.session().expiresAtMs()
        );
    }

    @Transactional
    public GuessPageData guesses(
            Actor actor,
            String requestedSessionId,
            String cursor,
            int limit,
            boolean refresh
    ) {
        ResolvedSession resolved = resolveSession(
                actor,
                GUESS_KIND,
                requestedSessionId,
                cursor,
                null,
                refresh,
                () -> guessSnapshot(actor)
        );
        PageSlice slice = slice(resolved, limit);
        List<RankedSuggestion> items = new ArrayList<>();
        for (int index = 0; index < slice.items().size(); index++) {
            SnapshotItem item = slice.items().get(index);
            int rank = resolved.offset() + index + 1;
            items.add(new RankedSuggestion(
                    item.entityId(),
                    item.text(),
                    rank,
                    item.source(),
                    exposure(actor.actorKey(), resolved.session(), item.entityId(), rank)
            ));
        }
        return new GuessPageData(
                resolved.session().id(),
                items,
                nextCursor(resolved.session(), slice),
                slice.hasMore(),
                ALGO_VERSION,
                POLICY_VERSION,
                resolved.session().expiresAtMs()
        );
    }

    @Transactional
    public SearchPageData search(Actor actor, String query, String cursor, int limit) {
        String normalizedQuery = normalize(query);
        String queryHash = RecommendationActorResolver.sha256Hex(normalizedQuery);
        ResolvedSession resolved = resolveSession(
                actor,
                SEARCH_KIND,
                null,
                cursor,
                queryHash,
                false,
                () -> searchSnapshot(actor, normalizedQuery)
        );
        PageSlice slice = slice(resolved, limit);
        Map<String, VideoCandidate> videos = videosByIds(actor.viewerUserId(), slice.items());
        return new SearchPageData(
                resolved.session().id(),
                cardItems(actor, resolved.session(), slice, videos),
                nextCursor(resolved.session(), slice),
                slice.hasMore(),
                ALGO_VERSION,
                POLICY_VERSION,
                resolved.session().expiresAtMs()
        );
    }

    public ServeSession requireSession(String sessionId) {
        return repository.findSession(sessionId)
                .orElseThrow(() -> badRequest("SESSION_NOT_FOUND", "Recommendation session was not found."));
    }

    private StoredHttpResponse replay(StoredRequest existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED",
                    "clientRequestId was already used with a different request.");
        }
        if (existing.status() == 0 || existing.responseJson().isBlank()) {
            throw conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "The client request is currently being processed.");
        }
        return new StoredHttpResponse(existing.status(), existing.responseJson());
    }

    private ResolvedSession resolveSession(
            Actor actor,
            String kind,
            String requestedSessionId,
            String cursor,
            String queryHash,
            boolean refresh,
            Supplier<Snapshot> snapshotSupplier
    ) {
        if (refresh) {
            return new ResolvedSession(createSession(actor.actorKey(), kind, queryHash, snapshotSupplier.get()), 0);
        }
        if (cursor != null && !cursor.isBlank()) {
            CursorClaims claims;
            try {
                claims = tokens.verifyCursor(cursor);
            } catch (TokenValidationException e) {
                throw badRequest("INVALID_CURSOR", "Cursor verification failed.");
            }
            if (!actor.actorKey().equals(claims.actorKey())) {
                throw badRequest("CURSOR_ACTOR_MISMATCH", "Cursor belongs to another actor.");
            }
            if (!kind.equals(claims.kind())) {
                throw badRequest("CURSOR_KIND_MISMATCH", "Cursor belongs to another endpoint.");
            }
            if (claims.expiresAtMs() <= System.currentTimeMillis()) {
                throw badRequest("CURSOR_EXPIRED", "Cursor has expired.");
            }
            if (!Objects.equals(queryHash, claims.queryHash())) {
                throw badRequest("CURSOR_QUERY_MISMATCH", "Cursor belongs to another search query.");
            }
            if (requestedSessionId != null
                    && !requestedSessionId.isBlank()
                    && !requestedSessionId.equals(claims.serveSessionId())) {
                throw badRequest("CURSOR_SESSION_MISMATCH", "Cursor and serveSessionId do not match.");
            }
            ServeSession session = validateSession(
                    repository.findSession(claims.serveSessionId())
                            .orElseThrow(() -> badRequest("SESSION_NOT_FOUND",
                                    "Recommendation session was not found.")),
                    actor.actorKey(),
                    kind,
                    queryHash
            );
            if (claims.offset() < 0 || claims.offset() > session.snapshot().items().size()) {
                throw badRequest("INVALID_CURSOR", "Cursor offset is invalid.");
            }
            return new ResolvedSession(session, claims.offset());
        }
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            ServeSession session = repository.findSession(requestedSessionId)
                    .map(value -> validateSession(value, actor.actorKey(), kind, queryHash))
                    .orElseThrow(() -> badRequest("SESSION_NOT_FOUND", "Recommendation session was not found."));
            return new ResolvedSession(session, 0);
        }
        return new ResolvedSession(createSession(actor.actorKey(), kind, queryHash, snapshotSupplier.get()), 0);
    }

    private ServeSession validateSession(
            ServeSession session,
            String actorKey,
            String kind,
            String queryHash
    ) {
        if (!session.actorKey().equals(actorKey)) {
            throw badRequest("SESSION_ACTOR_MISMATCH", "Recommendation session belongs to another actor.");
        }
        if (!session.kind().equals(kind)) {
            throw badRequest("SESSION_KIND_MISMATCH", "Recommendation session belongs to another endpoint.");
        }
        if (!Objects.equals(session.queryHash(), queryHash)) {
            throw badRequest("SESSION_QUERY_MISMATCH", "Recommendation session belongs to another search query.");
        }
        if (session.expiresAtMs() <= System.currentTimeMillis()) {
            throw badRequest("SESSION_EXPIRED", "Recommendation session has expired.");
        }
        return session;
    }

    private ServeSession createSession(String actorKey, String kind, String queryHash, Snapshot snapshot) {
        long now = System.currentTimeMillis();
        ServeSession session = new ServeSession(
                "rec_" + UUID.randomUUID().toString().replace("-", ""),
                actorKey,
                kind,
                queryHash,
                snapshot,
                now,
                now + sessionTtl.toMillis()
        );
        repository.saveSession(session);
        return session;
    }

    private Snapshot feedSnapshot(Actor actor) {
        return feedSnapshot(actor, UUID.randomUUID().toString());
    }

    Snapshot feedSnapshot(Actor actor, String seed) {
        List<VideoCandidate> videos = repository.eligibleVideos(actor.viewerUserId());
        Map<String, Double> interests = repository.interests(actor.actorKey());
        Map<String, Double> semantic = usableSemanticScores(semanticScores.feedScores(actor.actorKey()));
        double maxPopularity = videos.stream().mapToDouble(this::popularity).max().orElse(1.0);
        long newest = videos.stream().mapToLong(VideoCandidate::publishTime).max().orElse(0);
        List<ScoredVideo> scored = videos.stream().map(video -> {
            double interest = interestScore(interests, video.terms());
            double freshness = Math.exp(-Math.max(0, newest - video.publishTime()) / 86_400.0 / 180.0);
            double hotness = popularity(video) / Math.max(1.0, maxPopularity);
            double semanticAffinity = semanticStrength(semantic.getOrDefault(video.payload().id(), -1.0));
            double score = 0.48 * hotness
                    + 0.25 * freshness
                    + 0.55 * (1.0 - Math.exp(-interest / 5.0))
                    + 0.18 * semanticAffinity;
            return new ScoredVideo(video, score, interest, semanticAffinity);
        }).toList();
        Map<String, ScoredVideo> scoredById = new LinkedHashMap<>();
        scored.forEach(value -> scoredById.putIfAbsent(value.video().payload().id(), value));
        List<String> orderedIds = SeededSessionDiversifier.order(
                scored.stream()
                        .map(value -> new SeededSessionDiversifier.Candidate(
                                value.video().payload().id(),
                                value.video().authorUserId(),
                                value.score()
                        ))
                        .toList(),
                seed
        );
        List<SnapshotItem> ordered = orderedIds.stream()
                .map(scoredById::get)
                .map(value -> new SnapshotItem(
                        value.video().payload().id(),
                        feedSource(value),
                        null,
                        null
                ))
                .toList();
        return new Snapshot(ordered);
    }

    private Snapshot guessSnapshot(Actor actor) {
        Map<String, Double> interests = repository.interests(actor.actorKey());
        List<SuggestionCandidate> candidates = repository.suggestions();
        Map<String, Double> semantic = usableSemanticScores(semanticScores.suggestionScores(actor.actorKey()));
        double maximum = candidates.stream().mapToDouble(SuggestionCandidate::globalSignal).max().orElse(1.0);
        List<SnapshotItem> items = candidates.stream()
                .map(candidate -> {
                    double interest = interestScore(interests, List.of(candidate.text()));
                    double semanticAffinity = semanticStrength(
                            semantic.getOrDefault(candidate.id(), -1.0)
                    );
                    double score = candidate.globalSignal() / Math.max(1.0, maximum)
                            + (1.0 - Math.exp(-interest / 4.0)) * 3.0
                            + semanticAffinity * 0.75;
                    return new ScoredSuggestion(candidate, score, interest, semanticAffinity);
                })
                .sorted(Comparator.comparingDouble(ScoredSuggestion::score).reversed()
                        .thenComparing(value -> value.candidate().id()))
                .map(value -> new SnapshotItem(
                        value.candidate().id(),
                        suggestionSource(value),
                        value.candidate().text(),
                        null
                ))
                .toList();
        return new Snapshot(items);
    }

    private Snapshot searchSnapshot(Actor actor, String query) {
        Map<String, Double> interests = repository.interests(actor.actorKey());
        String seed = UUID.randomUUID().toString();
        Map<String, Double> semantic = usableSemanticScores(semanticScores.searchScores(query));
        if (semantic.isEmpty()) {
            List<SnapshotItem> lexicalItems = repository.eligibleVideos(actor.viewerUserId()).stream()
                    .map(video -> new ScoredSearch(
                            video,
                            searchRelevance(video, query),
                            interestScore(interests, video.terms()),
                            0.0
                    ))
                    .filter(value -> value.relevance() > 0)
                    .sorted(Comparator.comparingDouble((ScoredSearch value) ->
                                    value.relevance()
                                            + value.interest() * 0.05
                                            + popularity(value.video()) * 0.02
                                            + deterministicFraction(actor.actorKey() + "|" + seed + "|"
                                            + value.video().payload().id()) * 0.001)
                            .reversed()
                            .thenComparing(value -> value.video().payload().id()))
                    .map(value -> new SnapshotItem(
                            value.video().payload().id(),
                            "server_search",
                            null,
                            query
                    ))
                    .toList();
            return new Snapshot(lexicalItems);
        }

        Map<String, VideoCandidate> candidates = new LinkedHashMap<>();
        repository.eligibleVideos(actor.viewerUserId())
                .forEach(video -> candidates.put(video.payload().id(), video));
        Set<String> semanticIds = semantic.keySet();
        repository.eligibleVideosByIds(actor.viewerUserId(), semanticIds)
                .forEach(video -> candidates.putIfAbsent(video.payload().id(), video));

        List<SnapshotItem> items = candidates.values().stream()
                .map(video -> new ScoredSearch(video, searchRelevance(video, query),
                        interestScore(interests, video.terms()),
                        semanticStrength(semantic.getOrDefault(video.payload().id(), -1.0))))
                .filter(value -> value.relevance() > 0 || value.semanticAffinity() > 0)
                .sorted(Comparator.comparingDouble((ScoredSearch value) ->
                                value.relevance()
                                        + value.semanticAffinity() * 6.0
                                        + value.interest() * 0.05
                                        + popularity(value.video()) * 0.02
                                        + deterministicFraction(actor.actorKey() + "|" + seed + "|"
                                        + value.video().payload().id()) * 0.001)
                        .reversed()
                        .thenComparing(value -> value.video().payload().id()))
                .map(value -> new SnapshotItem(
                        value.video().payload().id(),
                        searchSource(value),
                        null,
                        query
                ))
                .toList();
        return new Snapshot(items);
    }

    private List<RankedCardItem> cardItems(
            Actor actor,
            ServeSession session,
            PageSlice slice,
            Map<String, VideoCandidate> videos
    ) {
        List<RankedCardItem> result = new ArrayList<>();
        for (int index = 0; index < slice.items().size(); index++) {
            SnapshotItem item = slice.items().get(index);
            VideoCandidate video = videos.get(item.entityId());
            if (video == null) {
                continue;
            }
            int rank = slice.offset() + index + 1;
            result.add(new RankedCardItem(
                    item.entityId(),
                    rank,
                    item.source(),
                    "organic",
                    POLICY_VERSION,
                    video.payload(),
                    exposure(actor.actorKey(), session, item.entityId(), rank),
                    item.relatedSearch()
            ));
        }
        return List.copyOf(result);
    }

    private String exposure(String actorKey, ServeSession session, String entityId, int rank) {
        return tokens.signExposure(new ExposureClaims(
                actorKey,
                session.kind(),
                entityId,
                session.id(),
                rank,
                session.expiresAtMs()
        ));
    }

    private String nextCursor(ServeSession session, PageSlice slice) {
        if (!slice.hasMore()) {
            return null;
        }
        return tokens.signCursor(new CursorClaims(
                session.actorKey(),
                session.kind(),
                session.id(),
                slice.nextOffset(),
                session.expiresAtMs(),
                session.queryHash()
        ));
    }

    private PageSlice slice(ResolvedSession resolved, int limit) {
        List<SnapshotItem> all = resolved.session().snapshot().items();
        int from = Math.min(resolved.offset(), all.size());
        int to = Math.min(from + limit, all.size());
        return new PageSlice(List.copyOf(all.subList(from, to)), from, to, to < all.size());
    }

    private Map<String, VideoCandidate> videosByIds(
            String viewerUserId,
            List<SnapshotItem> items
    ) {
        Map<String, VideoCandidate> values = new HashMap<>();
        repository.eligibleVideosByIds(
                        viewerUserId,
                        items.stream().map(SnapshotItem::entityId).toList()
                )
                .forEach(video -> values.put(video.payload().id(), video));
        return values;
    }

    private Map<String, Double> usableSemanticScores(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> filtered = new HashMap<>();
        values.forEach((id, score) -> {
            if (id != null
                    && !id.isBlank()
                    && score != null
                    && Double.isFinite(score)
                    && score >= semanticScores.minimumSimilarity()) {
                filtered.put(id, score);
            }
        });
        return Map.copyOf(filtered);
    }

    private double semanticStrength(double similarity) {
        if (!Double.isFinite(similarity) || similarity < semanticScores.minimumSimilarity()) {
            return 0.0;
        }
        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        (similarity - semanticScores.minimumSimilarity())
                                / (1.0 - semanticScores.minimumSimilarity())
                )
        );
    }

    private static String feedSource(ScoredVideo value) {
        if (value.interest() > 0 && value.semanticAffinity() > 0) {
            return "hybrid_personalized_ranker";
        }
        if (value.semanticAffinity() > 0) {
            return "semantic_personalized_ranker";
        }
        return value.interest() > 0 ? "personalized_ranker" : "organic_ranker";
    }

    private static String suggestionSource(ScoredSuggestion value) {
        if (value.interest() > 0 && value.semanticAffinity() > 0) {
            return "profile_semantic_and_global";
        }
        if (value.semanticAffinity() > 0) {
            return "semantic_profile_and_global";
        }
        return value.interest() > 0 ? "profile_and_global" : "global_database";
    }

    private static String searchSource(ScoredSearch value) {
        if (value.relevance() > 0 && value.semanticAffinity() > 0) {
            return "hybrid_search";
        }
        return value.relevance() > 0 ? "lexical_search" : "semantic_search";
    }

    private double searchRelevance(VideoCandidate video, String query) {
        double relevance = contains(video.payload().title(), query) ? 10.0 : 0.0;
        relevance += contains(video.payload().author(), query) ? 4.0 : 0.0;
        for (String term : video.terms()) {
            String normalized = normalize(term);
            if (normalized.equals(query)) {
                relevance += 8.0;
            } else if (normalized.contains(query) || query.contains(normalized)) {
                relevance += 4.0;
            }
        }
        return relevance;
    }

    private double interestScore(Map<String, Double> interests, List<String> terms) {
        double score = 0;
        for (String term : terms) {
            String normalized = normalize(term);
            score += interests.getOrDefault(normalized, 0.0);
        }
        return score;
    }

    private double popularity(VideoCandidate video) {
        return Math.log1p(video.viewCount())
                + Math.log1p(video.likeCount()) * 2.0
                + Math.log1p(video.favoriteCount()) * 1.5
                + Math.log1p(video.shareCount()) * 1.7;
    }

    private static boolean contains(String value, String query) {
        return value != null && normalize(value).contains(query);
    }

    public static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static double deterministicFraction(String value) {
        String hash = RecommendationActorResolver.sha256Hex(value);
        long bits = Long.parseUnsignedLong(hash.substring(0, 13), 16);
        return bits / (double) 0xFFFFFFFFFFFFFL;
    }

    private String serveMode(String scene) {
        String normalized = normalize(scene);
        return normalized.isBlank() ? "organic" : "organic_" + normalized.substring(0, Math.min(32, normalized.length()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize recommendation response.", e);
        }
    }

    private static RecommendationException badRequest(String code, String message) {
        return new RecommendationException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static RecommendationException conflict(String code, String message) {
        return new RecommendationException(HttpStatus.CONFLICT, code, message);
    }

    private record ResolvedSession(ServeSession session, int offset) {
    }

    private record PageSlice(
            List<SnapshotItem> items,
            int offset,
            int nextOffset,
            boolean hasMore
    ) {
    }

    private record ScoredVideo(
            VideoCandidate video,
            double score,
            double interest,
            double semanticAffinity
    ) {
    }

    private record ScoredSuggestion(
            SuggestionCandidate candidate,
            double score,
            double interest,
            double semanticAffinity
    ) {
    }

    private record ScoredSearch(
            VideoCandidate video,
            double relevance,
            double interest,
            double semanticAffinity
    ) {
    }
}
