package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record ImportedContentCommand(
        String id,
        String type,
        String title,
        String description,
        String authorUserId,
        String coverUrl,
        String sourceUrl,
        long publishTime,
        String rawJson
) {
}
