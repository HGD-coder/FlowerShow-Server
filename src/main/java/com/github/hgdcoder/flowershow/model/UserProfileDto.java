package com.github.hgdcoder.flowershow.model;

public record UserProfileDto(
        String id,
        String handle,
        String nickname,
        String avatarUrl,
        String profileBannerUrl,
        String bio,
        String location,
        String source,
        String profileVisibility,
        boolean ownProfile,
        boolean profileContentVisible,
        boolean following,
        boolean followedBy,
        boolean mutualFollow,
        long postCount,
        long receivedLikeCount,
        long followingCount,
        long followerCount,
        Long likedContentCount,
        Long favoriteContentCount,
        boolean likedTabVisible,
        boolean favoritesTabVisible,
        boolean showLikedOnProfile,
        boolean showFavoritesOnProfile,
        String createdAt,
        String updatedAt
) {
}
