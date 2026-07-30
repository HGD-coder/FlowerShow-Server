package com.github.hgdcoder.flowershow.model;

public record UserListItemDto(
        String id,
        String handle,
        String nickname,
        String avatarUrl,
        String bio,
        String location,
        long followerCount,
        long followingCount,
        boolean following,
        boolean followedBy,
        boolean mutualFollow
) {
}
