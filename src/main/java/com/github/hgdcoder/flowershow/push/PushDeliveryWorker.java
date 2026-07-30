package com.github.hgdcoder.flowershow.push;

import com.github.hgdcoder.flowershow.config.PushProperties;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flower-show.push.enabled", havingValue = "true")
public class PushDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(PushDeliveryWorker.class);

    private final PushDeliveryStore deliveryStore;
    private final PushSender pushSender;
    private final PushProperties properties;
    private final String workerInstanceId = UUID.randomUUID().toString();

    public PushDeliveryWorker(
            PushDeliveryStore deliveryStore,
            PushSender pushSender,
            PushProperties properties
    ) {
        this.deliveryStore = deliveryStore;
        this.pushSender = pushSender;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "#{@pushScheduleIntervalMillis}",
            initialDelayString = "#{@pushScheduleIntervalMillis}"
    )
    public void deliverScheduledBatch() {
        try {
            deliverOnce();
        } catch (RuntimeException error) {
            LOGGER.error("Push delivery cycle failed ({}).", error.getClass().getSimpleName());
        }
    }

    public int deliverOnce() {
        String claimOwner = workerInstanceId + ":" + UUID.randomUUID();
        deliveryStore.maintain(Instant.now());
        List<PushDeliveryClaim> deliveries = deliveryStore.claimBatch(claimOwner, Instant.now());
        Set<String> invalidDevices = new HashSet<>();
        for (PushDeliveryClaim delivery : deliveries) {
            if (invalidDevices.contains(delivery.deviceTokenId())) {
                continue;
            }
            PushSendResult result = safelySend(delivery);
            boolean completed = deliveryStore.complete(delivery, result, Instant.now());
            if (completed && result.outcome() == PushSendResult.Outcome.INVALID_RECIPIENT) {
                invalidDevices.add(delivery.deviceTokenId());
            }
        }
        return deliveries.size();
    }

    private PushSendResult safelySend(PushDeliveryClaim delivery) {
        try {
            return pushSender.send(delivery.toMessage(
                    properties.notificationTitle(),
                    properties.androidChannelId()
            ));
        } catch (RuntimeException error) {
            return PushSendResult.retryable(PushErrorCode.TRANSPORT_ERROR);
        }
    }
}
