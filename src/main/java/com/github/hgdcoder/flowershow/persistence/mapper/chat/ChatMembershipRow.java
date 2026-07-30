package com.github.hgdcoder.flowershow.persistence.mapper.chat;

import java.time.Instant;

public record ChatMembershipRow(
        String role,
        String state,
        long lastReadSequence,
        Instant lastReadAt
) {
}
