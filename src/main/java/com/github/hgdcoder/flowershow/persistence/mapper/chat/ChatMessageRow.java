package com.github.hgdcoder.flowershow.persistence.mapper.chat;

import java.time.Instant;

public record ChatMessageRow(
        String id,
        String conversationId,
        String senderUserId,
        String senderNickname,
        String senderAvatarUrl,
        String messageType,
        String body,
        String sharedContentId,
        String sharedContentTitle,
        String sharedContentCoverUrl,
        String sharedContentAuthorUserId,
        String sharedContentAuthorNickname,
        String clientMessageId,
        Instant createdAt,
        long sequence
) {
}
