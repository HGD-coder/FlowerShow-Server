package com.github.hgdcoder.flowershow.persistence.mapper.event;

public record FollowerPreferenceRow(
        String userId,
        boolean notifyNewContent
) {
}
