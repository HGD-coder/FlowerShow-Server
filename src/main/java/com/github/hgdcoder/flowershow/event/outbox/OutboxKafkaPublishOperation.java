package com.github.hgdcoder.flowershow.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.event.messaging.EventEnvelope;
import com.github.hgdcoder.flowershow.persistence.mapper.event.OutboxEventMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "flower-show.messaging.kafka.enabled", havingValue = "true")
public class OutboxKafkaPublishOperation {

    private final OutboxEventMapper outboxEventMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String domainTopic;
    private final int maxRetries;

    public OutboxKafkaPublishOperation(
            OutboxEventMapper outboxEventMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${flower-show.messaging.kafka.domain-topic:flower-show.domain-events.v1}") String domainTopic,
            @Value("${flower-show.events.outbox.max-retries:10}") int maxRetries
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.domainTopic = domainTopic;
        this.maxRetries = Math.max(1, maxRetries);
    }

    @Transactional
    public boolean publishNext() {
        List<OutboxEventRecord> rows = lockNextPendingEvent();
        if (rows.isEmpty()) {
            return false;
        }

        OutboxEventRecord row = rows.get(0);
        try {
            EventEnvelope event = row.toEnvelope(objectMapper);
            String value = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(domainTopic, event.partitionKey(), value).get(30, TimeUnit.SECONDS);
            outboxEventMapper.markProcessed(row.id());
        } catch (Exception e) {
            markRetry(row.id(), e);
        }
        return true;
    }

    private List<OutboxEventRecord> lockNextPendingEvent() {
        return outboxEventMapper.lockNextPendingEvent();
    }

    private void markRetry(String eventId, Exception error) {
        Integer retryCount = outboxEventMapper.findRetryCount(eventId);
        int nextRetry = (retryCount == null ? 0 : retryCount) + 1;
        long delaySeconds = Math.min(300, 1L << Math.min(8, nextRetry));
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        outboxEventMapper.markRetry(
                eventId,
                nextRetry >= maxRetries ? "dead" : "pending",
                nextRetry,
                Timestamp.from(Instant.now().plusSeconds(delaySeconds)),
                message.length() <= 2000 ? message : message.substring(0, 2000)
        );
    }
}
