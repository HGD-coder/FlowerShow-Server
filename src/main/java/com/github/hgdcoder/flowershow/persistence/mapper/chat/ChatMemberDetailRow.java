package com.github.hgdcoder.flowershow.persistence.mapper.chat;

import java.time.Instant;

public record ChatMemberDetailRow(
        String userId,
        String nickname,
        String avatarUrl,
        String role,
        Instant joinedAt
) {
}
