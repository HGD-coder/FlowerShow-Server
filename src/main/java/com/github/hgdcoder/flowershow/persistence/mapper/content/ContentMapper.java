package com.github.hgdcoder.flowershow.persistence.mapper.content;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ContentMapper {

    List<ContentRow> findAllFeedItems();

    List<ContentRow> findUserContent(@Param("userId") String userId);

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

    ContentMediaAssetRow findFirstAsset(
            @Param("contentId") String contentId,
            @Param("kind") String kind,
            @Param("progressiveOnly") boolean progressiveOnly
    );

    ContentMediaAssetRow findHlsAsset(@Param("contentId") String contentId);

    List<ContentMediaAssetRow> findQualityAssets(@Param("contentId") String contentId);

    List<ContentMediaAssetRow> findAlbumAssets(@Param("contentId") String contentId);

    long countUserById(@Param("userId") String userId);
}
