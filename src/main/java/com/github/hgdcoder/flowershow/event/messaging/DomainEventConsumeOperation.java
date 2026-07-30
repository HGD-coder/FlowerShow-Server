package com.github.hgdcoder.flowershow.event.messaging;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomainEventConsumeOperation {

    private static final String CONSUMER_NAME = "domain-event-handler-v1";

    private final DomainEventProcessor domainEventProcessor;
    private final ProcessedEventStore processedEventStore;

    public DomainEventConsumeOperation(
            DomainEventProcessor domainEventProcessor,
            ProcessedEventStore processedEventStore
    ) {
        this.domainEventProcessor = domainEventProcessor;
        this.processedEventStore = processedEventStore;
    }

    @Transactional
    public void consume(EventEnvelope event) {
        if (processedEventStore.isProcessed(CONSUMER_NAME, event.eventId())) {
            return;
        }
        domainEventProcessor.process(event);
        processedEventStore.markProcessed(CONSUMER_NAME, event.eventId());
    }
}
