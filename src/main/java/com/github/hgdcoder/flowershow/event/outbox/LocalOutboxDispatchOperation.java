package com.github.hgdcoder.flowershow.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.event.messaging.DomainEventProcessor;
import com.github.hgdcoder.flowershow.event.messaging.EventEnvelope;
import com.github.hgdcoder.flowershow.event.messaging.ProcessedEventStore;
import com.github.hgdcoder.flowershow.persistence.mapper.event.OutboxEventMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnExpression("${flower-show.messaging.local-dispatcher-enabled:true} and !${flower-show.messaging.kafka.enabled:false}")
public class LocalOutboxDispatchOperation {

    private static final String CONSUMER_NAME = "domain-event-handler-v1";

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;
    private final DomainEventProcessor domainEventProcessor;
    private final ProcessedEventStore processedEventStore;
    private final int maxRetries;

    public LocalOutboxDispatchOperation(
            OutboxEventMapper outboxEventMapper,
            ObjectMapper objectMapper,
            DomainEventProcessor domainEventProcessor,
            ProcessedEventStore processedEventStore,
            @Value("${flower-show.events.outbox.max-retries:10}") int maxRetries
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
        this.domainEventProcessor = domainEventProcessor;
        this.processedEventStore = processedEventStore;
        this.maxRetries = Math.max(1, maxRetries);
    }

    @Transactional
    public boolean dispatchNext() {
        List<OutboxEventRecord> rows = lockNextPendingEvent();
        if (rows.isEmpty()) {
            return false;
        }

        OutboxEventRecord row = rows.get(0);
        try {
            EventEnvelope event = row.toEnvelope(objectMapper);
            if (!processedEventStore.isProcessed(CONSUMER_NAME, event.eventId())) {
                domainEventProcessor.process(event);
                processedEventStore.markProcessed(CONSUMER_NAME, event.eventId());
            }
            markProcessed(row.id());
        } catch (Exception e) {
            markRetry(row.id(), e);
        }
        return true;
    }

    private List<OutboxEventRecord> lockNextPendingEvent() {
        return outboxEventMapper.lockNextPendingEvent();
    }

    private void markProcessed(String eventId) {
        outboxEventMapper.markProcessed(eventId);
    }

    private void markRetry(String eventId, Exception error) {
        Integer retryCount = outboxEventMapper.findRetryCount(eventId);
        int nextRetry = (retryCount == null ? 0 : retryCount) + 1;
        long delaySeconds = Math.min(300, 1L << Math.min(8, nextRetry));
        outboxEventMapper.markRetry(
                eventId,
                nextRetry >= maxRetries ? "dead" : "pending",
                nextRetry,
                Timestamp.from(Instant.now().plusSeconds(delaySeconds)),
                abbreviated(error)
        );
    }

    private static String abbreviated(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
