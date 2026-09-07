package com.github.hgdcoder.flowershow.repository;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CreateContentRequest;
import com.github.hgdcoder.flowershow.model.ProfileContentTab;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import java.util.List;
import java.util.Optional;

public interface ContentRepository {

    List<CardItemDto> findAllFeedItems(String viewerUserId);

    default List<CardItemDto> findAllFeedItems() {
        return findAllFeedItems(null);
    }

    List<CardItemDto> findFeedPage(String viewerUserId, int offset, int limit);

    List<VideoCardDto> findAllVideos(String viewerUserId);

    default List<VideoCardDto> findAllVideos() {
        return findAllVideos(null);
    }

    List<VideoCardDto> findVideoPage(String viewerUserId, int offset, int limit);

    long countPublishedPublicContent(String id);

    /**
     * Keyword search over published public content, ordered by relevance desc.
     * {@code query} is pre-normalized (trimmed, lowercase) and non-blank;
     * implementations must not return more than {@code limit} items.
     */
    List<CardItemDto> search(String query, String viewerUserId, int limit);

    List<CardItemDto> findUserContent(String userId, String viewerUserId);

    default List<CardItemDto> findUserContent(String userId) {
        return findUserContent(userId, null);
    }

    List<CardItemDto> findFollowingFeed(String userId, String viewerUserId);

    default List<CardItemDto> findFollowingFeed(String userId) {
        return findFollowingFeed(userId, null);
    }

    List<CardItemDto> findProfileContent(
            String userId,
            ProfileContentTab tab,
            boolean ownerView,
            String viewerUserId,
            int offset,
            int limit
    );

    default List<CardItemDto> findProfileContent(
            String userId,
            ProfileContentTab tab,
            boolean ownerView,
            int offset,
            int limit
    ) {
        return findProfileContent(userId, tab, ownerView, null, offset, limit);
    }

    long countProfileContent(String userId, ProfileContentTab tab, boolean ownerView);

    Optional<CardItemDto> findById(String id, String viewerUserId);

    default Optional<CardItemDto> findById(String id) {
        return findById(id, null);
    }

    CardItemDto saveContent(CreateContentRequest request);
}
