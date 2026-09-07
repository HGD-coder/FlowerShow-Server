package com.github.hgdcoder.flowershow.persistence.mapper.recommendation;

import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.BatchViewerStateRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.ContentAssetRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.ContentTagRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.InterestRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.SessionRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.StoredEventRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.StoredRequestRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.SuggestionSignalRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationRows.VideoRow;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RecommendationMapper {

    List<VideoRow> findEligibleVideos(@Param("limit") int limit);

    List<VideoRow> findEligibleVideosByIds(@Param("contentIds") List<String> contentIds);

    List<InterestRow> findInterests(@Param("actorKey") String actorKey);

    List<SuggestionSignalRow> findRecommendationWordSuggestionSignals();

    List<SuggestionSignalRow> findTagSuggestionSignals();

    List<String> findTags(@Param("contentId") String contentId);

    List<String> findRecommendationWords(@Param("contentId") String contentId);

    List<ContentTagRow> findTagsByContentIds(@Param("contentIds") List<String> contentIds);

    List<ContentTagRow> findRecommendationWordsByContentIds(@Param("contentIds") List<String> contentIds);

    List<ContentAssetRow> findAssetsByContentIds(@Param("contentIds") List<String> contentIds);

    List<BatchViewerStateRow> findViewerStates(
            @Param("viewerUserId") String viewerUserId,
            @Param("contentIds") List<String> contentIds
    );

    int insertSession(
            @Param("id") String id,
            @Param("actorKey") String actorKey,
            @Param("kind") String kind,
            @Param("queryHash") String queryHash,
            @Param("snapshotJson") String snapshotJson,
            @Param("createdAt") Instant createdAt,
            @Param("expiresAt") Instant expiresAt
    );

    SessionRow findSession(@Param("sessionId") String sessionId);

    StoredRequestRow findClientRequest(
            @Param("actorKey") String actorKey,
            @Param("endpoint") String endpoint,
            @Param("clientRequestId") String clientRequestId
    );

    int claimClientRequest(
            @Param("actorKey") String actorKey,
            @Param("endpoint") String endpoint,
            @Param("clientRequestId") String clientRequestId,
            @Param("requestHash") String requestHash,
            @Param("expiresAt") Instant expiresAt
    );

    int deleteExpiredClientRequest(
            @Param("actorKey") String actorKey,
            @Param("endpoint") String endpoint,
            @Param("clientRequestId") String clientRequestId,
            @Param("now") Instant now
    );

    int completeClientRequest(
            @Param("actorKey") String actorKey,
            @Param("endpoint") String endpoint,
            @Param("clientRequestId") String clientRequestId,
            @Param("status") int status,
            @Param("responseJson") String responseJson
    );

    StoredEventRow findEvent(
            @Param("actorKey") String actorKey,
            @Param("eventId") String eventId
    );

    int insertEvent(
            @Param("actorKey") String actorKey,
            @Param("eventId") String eventId,
            @Param("fingerprint") String fingerprint,
            @Param("type") String type,
            @Param("entityId") String entityId,
            @Param("sessionId") String sessionId,
            @Param("occurredAtMs") long occurredAtMs
    );

    int incrementInterest(
            @Param("actorKey") String actorKey,
            @Param("interestKey") String interestKey,
            @Param("delta") double delta
    );

    int insertInterest(
            @Param("actorKey") String actorKey,
            @Param("interestKey") String interestKey,
            @Param("weight") double weight
    );

    int deleteExpiredSessions(@Param("cutoff") Instant cutoff);

    int deleteExpiredClientRequests(@Param("cutoff") Instant cutoff);

    int deleteExpiredClientEvents(@Param("cutoff") Instant cutoff);
}
