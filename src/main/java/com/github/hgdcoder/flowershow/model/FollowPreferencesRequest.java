package com.github.hgdcoder.flowershow.model;

public record FollowPreferencesRequest(
        Boolean notifyNewContent,
        Boolean muted
) {
}
