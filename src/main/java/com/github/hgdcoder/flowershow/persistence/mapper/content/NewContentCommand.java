package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record NewContentCommand(
        String id,
        String type,
        String title,
        String description,
        String authorUserId,
        String coverUrl,
        String visibility,
        long publishTime
) {
}
