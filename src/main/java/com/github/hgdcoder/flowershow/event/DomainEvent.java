package com.github.hgdcoder.flowershow.event;

import java.util.Map;

public record DomainEvent(
        String eventType,
        String aggregateType,
        String aggregateId,
        Map<String, Object> payload
) {
    public static DomainEvent of(String eventType, String aggregateType, String aggregateId, Map<String, Object> payload) {
        return new DomainEvent(eventType, aggregateType, aggregateId, payload == null ? Map.of() : payload);
    }
}