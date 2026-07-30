package com.github.hgdcoder.flowershow.event.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.event.messaging.EventEnvelope;
import java.sql.Timestamp;
import java.util.Map;

public record OutboxEventRecord(
        String id,
        String eventType,
        String aggregateType,
        String aggregateId,
        String partitionKey,
        int schemaVersion,
        String payloadJson,
        Timestamp occurredAt
) {
    public EventEnvelope toEnvelope(ObjectMapper objectMapper) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() {
            });
            return new EventEnvelope(
                    id,
                    eventType,
                    aggregateType,
                    aggregateId,
                    partitionKey,
                    schemaVersion,
                    payload,
                    occurredAt.toInstant()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize outbox event " + id + ".", e);
        }
    }
}
