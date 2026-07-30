package com.github.hgdcoder.flowershow.persistence.mapper.event;

public record PublishedContentRow(
        String id,
        String authorUserId,
        String title,
        long publishTime,
        String status,
        String visibility,
        String fanoutMode,
        long followerCount
) {
}
