package com.github.hgdcoder.flowershow.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.event.DomainEvent;
import com.github.hgdcoder.flowershow.event.EventPublisher;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.persistence.mapper.event.OutboxEventInsert;
import com.github.hgdcoder.flowershow.persistence.mapper.event.OutboxEventMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void publish(DomainEvent event) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize event payload.", e);
        }

        outboxEventMapper.insertEvent(new OutboxEventInsert(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                partitionKey(event),
                payloadJson
        ));
    }

    private static String partitionKey(DomainEvent event) {
        if (EventTypes.CONTENT_PUBLISHED.equals(event.eventType())) {
            Object authorUserId = event.payload().get("authorUserId");
            if (authorUserId != null && !authorUserId.toString().isBlank()) {
                return authorUserId.toString();
            }
        }
        if (EventTypes.USER_FOLLOWED.equals(event.eventType())) {
            Object followingId = event.payload().get("followingId");
            if (followingId != null && !followingId.toString().isBlank()) {
                return followingId.toString();
            }
        }
        return event.aggregateId();
    }
}
