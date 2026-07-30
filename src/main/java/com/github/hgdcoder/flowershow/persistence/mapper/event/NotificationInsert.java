package com.github.hgdcoder.flowershow.persistence.mapper.event;

public record NotificationInsert(
        String id,
        String receiverUserId,
        String actorUserId,
        String type,
        String contentId,
        String commentId,
        String message,
        String eventId,
        String dedupeKey,
        String payloadJson
) {
}
