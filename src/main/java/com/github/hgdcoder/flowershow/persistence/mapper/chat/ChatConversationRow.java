package com.github.hgdcoder.flowershow.persistence.mapper.chat;

import java.time.Instant;

public record ChatConversationRow(
        String id,
        String type,
        String name,
        String ownerUserId,
        String directUserOneId,
        String directUserTwoId,
        String lastMessageId,
        Long lastMessageSequence,
        String state,
        Instant dissolvedAt,
        String dissolvedByUserId,
        Instant createdAt,
        Instant updatedAt
) {
}
