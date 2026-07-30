package com.github.hgdcoder.flowershow.persistence.mapper.event;

public record PushDeliveryRow(
        String deliveryId,
        String deviceTokenId,
        String pushProvider,
        String recipient,
        String recipientType,
        String notificationId,
        String type,
        String body,
        String actorUserId,
        String contentId,
        String commentId,
        int attemptCount
) {
}
