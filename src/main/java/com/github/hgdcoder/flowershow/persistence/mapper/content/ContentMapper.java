package com.github.hgdcoder.flowershow.persistence.mapper.content;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ContentMapper {

    List<ContentRow> findAllFeedItems();

    List<ContentRow> findFeedPage(
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    List<ContentRow> findVideoPage(
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * Keyword search scored in SQL. {@code query} must already be normalized
     * (trimmed, lowercased) and non-blank; results are ordered by score desc.
     */
    List<ContentRow> search(
            @Param("query") String query,
            @Param("limit") int limit
    );

    List<ContentRow> findUserContent(@Param("userId") String userId);

    String findProfileVisibility(@Param("userId") String userId);

    long countPublishedPublicContent(@Param("id") String id);

    List<ContentRow> findFollowingFeed(@Param("userId") String userId);

    List<ContentRow> findProfilePosts(
            @Param("userId") String userId,
            @Param("ownerView") boolean ownerView,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    List<ContentRow> findProfileLiked(
            @Param("userId") String userId,
            @Param("ownerView") boolean ownerView,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    List<ContentRow> findProfileFavorites(
            @Param("userId") String userId,
            @Param("ownerView") boolean ownerView,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countProfilePosts(
            @Param("userId") String userId,
            @Param("ownerView") boolean ownerView
    );

    long countProfileLiked(
            @Param("userId") String userId,
            @Param("ownerView") boolean ownerView
    );

    long countProfileFavorites(
            @Param("userId") String userId,
            @Param("ownerView") boolean ownerView
    );

    ContentRow findById(@Param("id") String id);

    List<ViewerStateRow> findViewerStates(
            @Param("viewerUserId") String viewerUserId,
            @Param("contentIds") List<String> contentIds
    );

    void insertContent(@Param("content") NewContentCommand content);

    void insertContentStats(@Param("contentId") String contentId);

    void insertTags(
            @Param("contentId") String contentId,
            @Param("values") List<OrderedContentValue> values
    );

    void insertRecommendWords(
            @Param("contentId") String contentId,
            @Param("values") List<OrderedContentValue> values
    );

    void insertMediaAssets(@Param("assets") List<NewContentMediaAsset> assets);

    List<String> findTags(@Param("contentId") String contentId);

    List<String> findRecommendWords(@Param("contentId") String contentId);

    List<ContentTagRow> findTagsByContentIds(@Param("contentIds") List<String> contentIds);

    List<ContentTagRow> findRecommendWordsByContentIds(@Param("contentIds") List<String> contentIds);

    List<ContentAssetRow> findAssetsByContentIds(@Param("contentIds") List<String> contentIds);

    ContentMediaAssetRow findFirstAsset(
            @Param("contentId") String contentId,
            @Param("kind") String kind,
            @Param("progressiveOnly") boolean progressiveOnly
    );

    ContentMediaAssetRow findHlsAsset(@Param("contentId") String contentId);

    List<ContentMediaAssetRow> findQualityAssets(@Param("contentId") String contentId);

    List<ContentMediaAssetRow> findAlbumAssets(@Param("contentId") String contentId);

    long countUserById(@Param("userId") String userId);

    record ContentTagRow(String contentId, String tag, int sortOrder) {
    }

    record ContentAssetRow(
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
