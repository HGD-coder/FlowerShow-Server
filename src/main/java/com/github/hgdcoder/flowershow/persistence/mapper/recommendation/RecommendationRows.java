package com.github.hgdcoder.flowershow.persistence.mapper.recommendation;

import java.time.Instant;

public final class RecommendationRows {

    private RecommendationRows() {
    }

    public record VideoRow(
            String id,
            String title,
            String authorUserId,
            String coverUrl,
            String sourceUrl,
            long publishTime,
            String nickname,
            String avatarUrl,
            String sourceUserId,
            String location,
            int likeCount,
            int commentCount,
            int favoriteCount,
            int shareCount,
            long viewCount
    ) {
    }

    public record InterestRow(String interestKey, double weight) {
    }

    public record SuggestionSignalRow(
            String contentId,
            String term,
            long likeCount,
            long favoriteCount,
            long shareCount,
            long viewCount
    ) {
    }

    public record SessionRow(
            String id,
            String actorKey,
            String kind,
            String queryHash,
            String snapshotJson,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record StoredRequestRow(
            String requestHash,
            int responseStatus,
            String responseJson,
            Instant expiresAt
    ) {
    }

    public record StoredEventRow(String eventFingerprint) {
    }

    public record BatchViewerStateRow(String contentId, boolean liked, boolean favorited) {
    }

    public record ContentTagRow(String contentId, String tag, int sortOrder) {
    }

    public record ContentAssetRow(
            String contentId,
            String kind,
            String quality,
            String url,
            String storageKey,
            String deliveryType,
            int sortOrder
    ) {
    }
}
