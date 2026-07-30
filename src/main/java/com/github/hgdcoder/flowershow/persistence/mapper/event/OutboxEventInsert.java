package com.github.hgdcoder.flowershow.persistence.mapper.event;

public record OutboxEventInsert(
        String id,
        String eventType,
        String aggregateType,
        String aggregateId,
        String partitionKey,
        String payloadJson
) {
}
