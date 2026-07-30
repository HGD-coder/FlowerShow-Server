package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record ContentRow(
        String id,
        String type,
        String title,
        String authorUserId,
        String nickname,
        String avatarUrl,
        String sourceUserId,
        String location,
        String coverUrl,
        String sourceUrl,
        long publishTime,
        int likeCount,
        int commentCount,
        int favoriteCount,
        int shareCount
) {
}
