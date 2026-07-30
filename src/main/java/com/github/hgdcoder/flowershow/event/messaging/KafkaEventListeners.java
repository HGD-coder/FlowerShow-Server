package com.github.hgdcoder.flowershow.event.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flower-show.messaging.kafka.enabled", havingValue = "true")
public class KafkaEventListeners {

    private final ObjectMapper objectMapper;
    private final DomainEventConsumeOperation domainEventConsumeOperation;
    private final FanoutChunkProcessor fanoutChunkProcessor;

    public KafkaEventListeners(
            ObjectMapper objectMapper,
            DomainEventConsumeOperation domainEventConsumeOperation,
            FanoutChunkProcessor fanoutChunkProcessor
    ) {
        this.objectMapper = objectMapper;
        this.domainEventConsumeOperation = domainEventConsumeOperation;
        this.fanoutChunkProcessor = fanoutChunkProcessor;
    }

    @KafkaListener(
            topics = "${flower-show.messaging.kafka.domain-topic:flower-show.domain-events.v1}",
            groupId = "${spring.kafka.consumer.group-id:flower-show-server-v1}-domain"
    )
    public void onDomainEvent(String value) {
        domainEventConsumeOperation.consume(read(value, EventEnvelope.class));
    }

    @KafkaListener(
            topics = "${flower-show.messaging.kafka.fanout-topic:flower-show.feed-fanout.v1}",
            groupId = "${spring.kafka.consumer.group-id:flower-show-server-v1}-fanout"
    )
    public void onFanoutChunk(String value) {
        fanoutChunkProcessor.process(read(value, FanoutChunkMessage.class));
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize Kafka message as " + type.getSimpleName() + ".", e);
        }
    }
}
