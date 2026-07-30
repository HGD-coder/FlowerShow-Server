package com.github.hgdcoder.flowershow.model;

public record FollowPreferencesDto(
        String targetUserId,
        boolean following,
        boolean notifyNewContent,
        boolean muted
) {
}
