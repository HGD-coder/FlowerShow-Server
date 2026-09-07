package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.BatchViewerStateRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.ContentAssetRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.ContentTagRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.InterestRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.SessionRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.StoredEventRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.StoredRequestRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.SuggestionSignalRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.VideoRow;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ServeSession;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class RecommendationRepository {

    private static final int MAX_CANDIDATES = 500;

    private final RecommendationMapper mapper;
    private final ObjectMapper objectMapper;
    private final String mediaPublicBaseUrl;

    public RecommendationRepository(
            RecommendationMapper mapper,
            ObjectMapper objectMapper,
            @Value("${flower-show.media.public-base-url:}") String mediaPublicBaseUrl
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.mediaPublicBaseUrl = mediaPublicBaseUrl == null ? "" : mediaPublicBaseUrl.trim();
    }

    public List<VideoCandidate> eligibleVideos(String viewerUserId) {
        return toCandidates(mapper.findEligibleVideos(MAX_CANDIDATES), viewerUserId);
    }

    public List<VideoCandidate> eligibleVideosByIds(
            String viewerUserId,
            Collection<String> contentIds
    ) {
        List<String> ids = sanitizedIds(contentIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return toCandidates(mapper.findEligibleVideosByIds(ids), viewerUserId);
    }

    public Map<String, Double> interests(String actorKey) {
        Map<String, Double> values = new HashMap<>();
        for (InterestRow row : mapper.findInterests(actorKey)) {
            values.put(row.interestKey(), row.weight());
        }
        return values;
    }

    public List<SuggestionCandidate> suggestions() {
        Map<String, SuggestionAccumulator> merged = new HashMap<>();
        collectSuggestions(mapper.findRecommendationWordSuggestionSignals(), merged);
        collectSuggestions(mapper.findTagSuggestionSignals(), merged);
        return merged.entrySet().stream()
                .map(entry -> {
                    SuggestionAccumulator value = entry.getValue();
                    return new SuggestionCandidate(
                            suggestionId(entry.getKey()),
                            entry.getKey(),
                            value.score,
                            value.videoIds.size()
                    );
                })
                .toList();
    }

    public List<String> contentTerms(String contentId) {
        Set<String> terms = new LinkedHashSet<>();
        terms.addAll(mapper.findTags(contentId));
        terms.addAll(mapper.findRecommendationWords(contentId));
        return List.copyOf(terms);
    }

    public void saveSession(ServeSession session) {
        mapper.insertSession(
                session.id(),
                session.actorKey(),
                session.kind(),
                session.queryHash(),
                writeJson(session.snapshot()),
                Instant.ofEpochMilli(session.createdAtMs()),
                Instant.ofEpochMilli(session.expiresAtMs())
        );
    }

    public Optional<ServeSession> findSession(String sessionId) {
        SessionRow row = mapper.findSession(sessionId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new ServeSession(
                row.id(),
                row.actorKey(),
                row.kind(),
                row.queryHash(),
                readSnapshot(row.snapshotJson()),
                row.createdAt().toEpochMilli(),
                row.expiresAt().toEpochMilli()
        ));
    }

    public Optional<StoredRequest> findClientRequest(
            String actorKey,
            String endpoint,
            String clientRequestId
    ) {
        StoredRequestRow row = mapper.findClientRequest(actorKey, endpoint, clientRequestId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredRequest(
                row.requestHash(),
                row.responseStatus(),
                row.responseJson(),
                row.expiresAt().toEpochMilli()
        ));
    }

    public int claimClientRequest(
            String actorKey,
            String endpoint,
            String clientRequestId,
            String requestHash,
            long expiresAtMs
    ) {
        return mapper.claimClientRequest(
                actorKey,
                endpoint,
                clientRequestId,
                requestHash,
                Instant.ofEpochMilli(expiresAtMs)
        );
    }

    public void deleteExpiredClientRequest(
            String actorKey,
            String endpoint,
            String clientRequestId,
            long nowMs
    ) {
        mapper.deleteExpiredClientRequest(
                actorKey,
                endpoint,
                clientRequestId,
                Instant.ofEpochMilli(nowMs)
        );
    }

    public void completeClientRequest(
            String actorKey,
            String endpoint,
            String clientRequestId,
            int status,
            String responseJson
    ) {
        mapper.completeClientRequest(actorKey, endpoint, clientRequestId, status, responseJson);
    }

    public Optional<StoredEvent> findEvent(String actorKey, String eventId) {
        StoredEventRow row = mapper.findEvent(actorKey, eventId);
        return row == null
                ? Optional.empty()
                : Optional.of(new StoredEvent(row.eventFingerprint()));
    }

    public int insertEvent(
            String actorKey,
            String eventId,
            String fingerprint,
            String type,
            String entityId,
            String sessionId,
            long occurredAtMs
    ) {
        return mapper.insertEvent(
                actorKey,
                eventId,
                fingerprint,
                type,
                entityId,
                sessionId,
                occurredAtMs
        );
    }

    public void addInterest(String actorKey, String term, double delta) {
        String normalized = normalizeTerm(term);
        if (normalized.isBlank() || normalized.length() > 200 || delta <= 0) {
            return;
        }
        int updated = mapper.incrementInterest(actorKey, normalized, delta);
        if (updated > 0) {
            return;
        }
        int inserted = mapper.insertInterest(actorKey, normalized, delta);
        if (inserted == 0) {
            mapper.incrementInterest(actorKey, normalized, delta);
        }
    }

    private void collectSuggestions(
            List<SuggestionSignalRow> rows,
            Map<String, SuggestionAccumulator> merged
    ) {
        for (SuggestionSignalRow row : rows) {
            String term = normalizeTerm(row.term());
            if (term.isBlank() || term.length() > 200) {
                continue;
            }
            SuggestionAccumulator accumulator = merged.computeIfAbsent(
                    term,
                    ignored -> new SuggestionAccumulator()
            );
            accumulator.videoIds.add(row.contentId());
            accumulator.score += 1.0
                    + Math.log1p(row.viewCount()) * 0.05
                    + Math.log1p(row.likeCount()) * 0.08
                    + Math.log1p(row.favoriteCount() + row.shareCount()) * 0.06;
        }
    }

    private List<VideoCandidate> toCandidates(List<VideoRow> rows, String viewerUserId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> contentIds = rows.stream().map(VideoRow::id).distinct().toList();

        Map<String, List<String>> tagsById = new HashMap<>();
        for (ContentTagRow tag : mapper.findTagsByContentIds(contentIds)) {
            tagsById.computeIfAbsent(tag.contentId(), ignored -> new ArrayList<>()).add(tag.tag());
        }
        Map<String, List<String>> wordsById = new HashMap<>();
        for (ContentTagRow word : mapper.findRecommendationWordsByContentIds(contentIds)) {
            wordsById.computeIfAbsent(word.contentId(), ignored -> new ArrayList<>()).add(word.tag());
        }
        Map<String, List<ContentAssetRow>> assetsById = new HashMap<>();
        for (ContentAssetRow asset : mapper.findAssetsByContentIds(contentIds)) {
            assetsById.computeIfAbsent(asset.contentId(), ignored -> new ArrayList<>()).add(asset);
        }
        Map<String, ViewerState> viewerStates = viewerStates(contentIds, viewerUserId);

        return rows.stream()
                .map(row -> toCandidate(
                        row,
                        viewerUserId,
                        tagsById.getOrDefault(row.id(), List.of()),
                        wordsById.getOrDefault(row.id(), List.of()),
                        assetsById.getOrDefault(row.id(), List.of()),
                        viewerStates.getOrDefault(row.id(), ViewerState.NONE)
                ))
                .toList();
    }

    private Map<String, ViewerState> viewerStates(List<String> contentIds, String viewerUserId) {
        if (viewerUserId == null || viewerUserId.isBlank()) {
            return Map.of();
        }
        Map<String, ViewerState> states = new HashMap<>();
        for (BatchViewerStateRow row : mapper.findViewerStates(viewerUserId.trim(), contentIds)) {
            states.put(row.contentId(), new ViewerState(row.liked(), row.favorited()));
        }
        return states;
    }

    private VideoCandidate toCandidate(
            VideoRow row,
            String viewerUserId,
            List<String> tags,
            List<String> words,
            List<ContentAssetRow> assets,
            ViewerState viewerState
    ) {
        VideoCardDto payload = new VideoCardDto(
                "video",
                row.id(),
                row.title(),
                row.nickname(),
                row.avatarUrl(),
                firstProgressiveUrl(assets, "video"),
                row.coverUrl(),
                firstUrl(assets, "music"),
                row.likeCount(),
                row.commentCount(),
                row.favoriteCount(),
                row.shareCount(),
                tags,
                words,
                row.authorUserId(),
                row.sourceUserId(),
                row.location(),
                row.sourceUrl(),
                row.publishTime(),
                qualityUrls(assets),
                hlsUrl(assets),
                row.authorUserId(),
                viewerState.liked(),
                viewerState.favorited()
        );
        Set<String> terms = new LinkedHashSet<>(tags);
        terms.addAll(words);
        return new VideoCandidate(
                payload,
                row.authorUserId(),
                row.publishTime(),
                row.viewCount(),
                row.likeCount(),
                row.favoriteCount(),
                row.shareCount(),
                List.copyOf(terms)
        );
    }

    private String firstUrl(List<ContentAssetRow> assets, String kind) {
        return assets.stream()
                .filter(asset -> kind.equals(asset.kind()))
                .map(asset -> resolveMediaUrl(asset.url(), asset.storageKey()))
                .findFirst()
                .orElse("");
    }

    private String firstProgressiveUrl(List<ContentAssetRow> assets, String kind) {
        return assets.stream()
                .filter(asset -> kind.equals(asset.kind()))
                .filter(asset -> asset.deliveryType() == null
                        || "progressive".equalsIgnoreCase(asset.deliveryType()))
                .map(asset -> resolveMediaUrl(asset.url(), asset.storageKey()))
                .findFirst()
                .orElse("");
    }

    private String hlsUrl(List<ContentAssetRow> assets) {
        ContentAssetRow first = null;
        ContentAssetRow auto = null;
        for (ContentAssetRow asset : assets) {
            if (!"video".equals(asset.kind()) || !"hls".equalsIgnoreCase(asset.deliveryType())) {
                continue;
            }
            if (first == null) {
                first = asset;
            }
            if ("auto".equals(asset.quality()) && auto == null) {
                auto = asset;
            }
        }
        ContentAssetRow chosen = auto != null ? auto : first;
        return chosen == null ? null : resolveMediaUrl(chosen.url(), chosen.storageKey());
    }

    private Map<String, String> qualityUrls(List<ContentAssetRow> assets) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (ContentAssetRow asset : assets) {
            if (!"video".equals(asset.kind()) || asset.quality() == null) {
                continue;
            }
            if (asset.deliveryType() != null && !"progressive".equalsIgnoreCase(asset.deliveryType())) {
                continue;
            }
            urls.put(asset.quality(), resolveMediaUrl(asset.url(), asset.storageKey()));
        }
        return urls.isEmpty() ? null : urls;
    }

    private String resolveMediaUrl(String url, String storageKey) {
        if (storageKey != null && !storageKey.isBlank() && !mediaPublicBaseUrl.isBlank()) {
            return mediaPublicBaseUrl.replaceAll("/+$", "")
                    + "/"
                    + storageKey.replaceAll("^/+", "");
        }
        return url;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot persist recommendation snapshot.", e);
        }
    }

    private Snapshot readSnapshot(String value) {
        try {
            return objectMapper.readValue(value, Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read recommendation snapshot.", e);
        }
    }

    private static List<String> sanitizedIds(Collection<String> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return List.of();
        }
        return contentIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    public static String suggestionId(String term) {
        return "sug_" + RecommendationActorResolver.sha256Hex(normalizeTerm(term)).substring(0, 24);
    }

    public static String normalizeTerm(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public record VideoCandidate(
            VideoCardDto payload,
            String authorUserId,
            long publishTime,
            long viewCount,
            long likeCount,
            long favoriteCount,
            long shareCount,
            List<String> terms
    ) {
    }

    public record SuggestionCandidate(String id, String text, double globalSignal, int videoCount) {
    }

    public record StoredRequest(String requestHash, int status, String responseJson, long expiresAtMs) {
    }

    public record StoredEvent(String fingerprint) {
    }

    private record ViewerState(boolean liked, boolean favorited) {
        private static final ViewerState NONE = new ViewerState(false, false);
    }

    private static final class SuggestionAccumulator {
        private final Set<String> videoIds = new LinkedHashSet<>();
        private double score;
    }
}
