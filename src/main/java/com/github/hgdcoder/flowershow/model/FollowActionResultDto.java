package com.github.hgdcoder.flowershow.model;

public record FollowActionResultDto(
        boolean changed,
        String targetUserId,
        boolean following,
        boolean followedBy,
        boolean mutualFollow,
        long followerCount,
        long followingCount
) {
}
