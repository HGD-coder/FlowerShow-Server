package com.github.hgdcoder.flowershow.model;

public record CommentDto(
        String id,
        String contentId,
        String userId,
        String nickname,
        String avatarUrl,
        String parentId,
        String body,
        int likeCount,
        String createdAt
) {
}