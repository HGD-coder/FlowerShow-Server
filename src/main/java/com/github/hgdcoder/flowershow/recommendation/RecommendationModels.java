package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import java.util.List;
import java.util.Map;

public final class RecommendationModels {

    private RecommendationModels() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ApiEnvelope<T>(
            String requestId,
            String traceId,
            long serverTimeMs,
            T data,
            ApiError error
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ApiError(String code, String message, Map<String, Object> details) {
    }

    public record FeedPageRequest(
            String clientRequestId,
            String serveSessionId,
            String cursor,
            Integer limit,
            String scene,
            Boolean refresh
    ) {
    }

    public record GuessPageRequest(
            String clientRequestId,
            String serveSessionId,
            String cursor,
            Integer limit,
            Boolean refresh
    ) {
    }

    public record SearchVideosRequest(
            String clientRequestId,
            String query,
            String cursor,
            Integer limit
    ) {
    }

    public record FeedPageData(
            String serveSessionId,
            List<RankedCardItem> items,
            String nextCursor,
            boolean hasMore,
            String serveMode,
            String algoVersion,
            String policyVersion,
            long expiresAtMs
    ) {
    }

    public record SearchPageData(
            String serveSessionId,
            List<RankedCardItem> items,
            String nextCursor,
            boolean hasMore,
            String algoVersion,
            String policyVersion,
            long expiresAtMs
    ) {
    }

    public record GuessPageData(
            String serveSessionId,
            List<RankedSuggestion> items,
            String nextCursor,
            boolean hasMore,
            String algoVersion,
            String policyVersion,
            long expiresAtMs
    ) {
    }

    public record RankedCardItem(
            String id,
            int rank,
            String source,
            String deliveryType,
            String policyVersion,
            VideoCardDto payload,
            String exposureToken,
            String relatedSearch
    ) {
    }

    public record RankedSuggestion(
            String id,
            String text,
            int rank,
            String source,
            String exposureToken
    ) {
    }

    public record EventBatchRequest(List<ClientEvent> events) {
    }

    public record ClientEvent(
            String eventId,
            String type,
            Long occurredAtMs,
            String exposureToken,
            String contentId,
            String suggestionId,
            String query,
            Long watchMs,
            String playbackId,
            Long sequence
    ) {
    }

    public record EventBatchData(List<EventResult> results) {
    }

    public record EventResult(String eventId, String status, String code, String message) {
        public static EventResult accepted(String eventId) {
            return new EventResult(eventId, "accepted", null, null);
        }

        public static EventResult duplicate(String eventId) {
            return new EventResult(eventId, "duplicate", null, null);
        }

        public static EventResult rejected(String eventId, String code, String message) {
            return new EventResult(eventId, "rejected", code, message);
        }
    }

    public record Actor(String actorKey, String viewerUserId) {
    }

    public record Snapshot(List<SnapshotItem> items) {
    }

    public record SnapshotItem(String entityId, String source, String text, String relatedSearch) {
    }

    public record ServeSession(
            String id,
            String actorKey,
            String kind,
            String queryHash,
            Snapshot snapshot,
            long createdAtMs,
            long expiresAtMs
    ) {
    }

    public record StoredHttpResponse(int status, String json) {
    }
}
