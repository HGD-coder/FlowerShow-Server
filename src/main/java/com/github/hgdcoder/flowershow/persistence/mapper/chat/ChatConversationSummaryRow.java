package com.github.hgdcoder.flowershow.persistence.mapper.chat;

import java.time.Instant;

public record ChatConversationSummaryRow(
        String id,
        String type,
        String name,
        String ownerUserId,
        String state,
        Instant dissolvedAt,
        Instant updatedAt,
        Instant activityAt,
        String displayName,
        String displayAvatarUrl,
        String messageId,
        String messageConversationId,
        String messageSenderUserId,
        String messageSenderNickname,
        String messageSenderAvatarUrl,
        String messageType,
        String messageBody,
        String messageSharedContentId,
        String messageSharedContentTitle,
        String messageSharedContentCoverUrl,
        String messageSharedContentAuthorUserId,
        String messageSharedContentAuthorNickname,
        String messageClientMessageId,
        Instant messageCreatedAt,
        long unreadCount
) {
}
