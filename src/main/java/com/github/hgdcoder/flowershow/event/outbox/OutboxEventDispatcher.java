package com.github.hgdcoder.flowershow.event.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${flower-show.messaging.local-dispatcher-enabled:true} and !${flower-show.messaging.kafka.enabled:false}")
public class OutboxEventDispatcher {

    private final LocalOutboxDispatchOperation dispatchOperation;
    private final int batchSize;

    public OutboxEventDispatcher(
            LocalOutboxDispatchOperation dispatchOperation,
            @Value("${flower-show.events.outbox.batch-size:100}") int batchSize
    ) {
        this.dispatchOperation = dispatchOperation;
        this.batchSize = Math.min(1000, Math.max(1, batchSize));
    }

    @Scheduled(
            fixedDelayString = "${flower-show.events.outbox.fixed-delay-ms:100}",
            initialDelayString = "${flower-show.events.outbox.initial-delay-ms:100}"
    )
    public void dispatchPendingEvents() {
        for (int i = 0; i < batchSize; i++) {
            if (!dispatchOperation.dispatchNext()) {
                return;
            }
        }
    }
}
