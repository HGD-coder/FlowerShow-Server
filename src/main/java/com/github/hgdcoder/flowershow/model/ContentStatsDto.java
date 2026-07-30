package com.github.hgdcoder.flowershow.model;

public record ContentStatsDto(
        String contentId,
        int likeCount,
        int commentCount,
        int favoriteCount,
        int shareCount,
        int viewCount
) {
}