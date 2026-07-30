package com.github.hgdcoder.flowershow.event.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flower-show.messaging.kafka.enabled", havingValue = "true")
public class OutboxKafkaPublisher {

    private final OutboxKafkaPublishOperation publishOperation;
    private final int batchSize;

    public OutboxKafkaPublisher(
            OutboxKafkaPublishOperation publishOperation,
            @Value("${flower-show.events.outbox.batch-size:100}") int batchSize
    ) {
        this.publishOperation = publishOperation;
        this.batchSize = Math.min(1000, Math.max(1, batchSize));
    }

    @Scheduled(
            fixedDelayString = "${flower-show.events.outbox.fixed-delay-ms:100}",
            initialDelayString = "${flower-show.events.outbox.initial-delay-ms:100}"
    )
    public void publishPendingEvents() {
        for (int i = 0; i < batchSize; i++) {
            if (!publishOperation.publishNext()) {
                return;
            }
        }
    }
}
