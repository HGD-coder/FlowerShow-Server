package com.github.hgdcoder.flowershow.event.messaging;

import java.time.Instant;
import java.util.Map;

public record EventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String partitionKey,
        int schemaVersion,
        Map<String, Object> payload,
        Instant occurredAt
) {
}
