package com.github.hgdcoder.flowershow.event.messaging;

import java.util.List;

public record FanoutChunkMessage(
        String chunkId,
        String sourceEventId,
        String contentId,
        String authorUserId,
        String contentTitle,
        long score,
        List<FanoutRecipient> recipients
) {
}
