package com.github.hgdcoder.flowershow.model;

public record NotificationDto(
        String id,
        String receiverUserId,
        String actorUserId,
        String actorNickname,
        String actorAvatarUrl,
        String type,
        String contentId,
        String commentId,
        String message,
        boolean read,
        String createdAt
) {
}
