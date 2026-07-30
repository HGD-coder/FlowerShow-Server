package com.github.hgdcoder.flowershow.push;

public record PushDeliveryClaim(
        String deliveryId,
        String deviceTokenId,
        PushProvider provider,
        String recipient,
        PushRecipientType recipientType,
        String notificationId,
        String type,
        String body,
        String actorUserId,
        String contentId,
        String commentId,
        int attemptCount,
        String claimOwner
) {
    public PushDeliveryMessage toMessage(String title, String androidChannelId) {
        return new PushDeliveryMessage(
                provider,
                recipient,
                recipientType,
                notificationId,
                type,
                title,
                body,
                actorUserId,
                contentId,
                commentId,
                androidChannelId
        );
    }

    @Override
    public String toString() {
        return "PushDeliveryClaim[deliveryId=" + deliveryId
                + ", notificationId=" + notificationId
                + ", deviceTokenId=" + deviceTokenId
                + ", provider=" + provider
                + ", attemptCount=" + attemptCount
                + "]";
    }
}
