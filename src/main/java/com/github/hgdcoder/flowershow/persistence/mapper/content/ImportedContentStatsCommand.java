package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record ImportedContentStatsCommand(
        String contentId,
        int likeCount,
        int commentCount,
        int favoriteCount,
        int shareCount
) {
}
