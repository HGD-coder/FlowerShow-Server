package com.github.hgdcoder.flowershow.push;

import com.github.hgdcoder.flowershow.config.PushProperties;
import com.github.hgdcoder.flowershow.persistence.mapper.event.PushDeliveryMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.event.PushDeliveryRow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushDeliveryStore {

    private static final String MESSAGE_EXPIRED = "MESSAGE_EXPIRED";
    private static final String MAX_ATTEMPTS = "MAX_ATTEMPTS";
    private static final String CLAIM_TIMEOUT_MAX_ATTEMPTS = "CLAIM_TIMEOUT_MAX_ATTEMPTS";
    private static final String RECIPIENT_DISABLED = "RECIPIENT_DISABLED";

    private final PushDeliveryMapper pushDeliveryMapper;
    private final PushProperties properties;

    public PushDeliveryStore(PushDeliveryMapper pushDeliveryMapper, PushProperties properties) {
        this.pushDeliveryMapper = pushDeliveryMapper;
        this.properties = properties;
    }

    @Transactional
    public void maintain(Instant now) {
        Timestamp claimDeadline = Timestamp.from(now.minus(properties.claimTimeout()));
        Timestamp expiryCutoff = Timestamp.from(now.minus(properties.messageMaxAge()));

        terminateDisabledRecipients();
        expireStaleClaims(claimDeadline, expiryCutoff);
        terminateExhaustedStaleClaims(claimDeadline);
        expireQueuedDeliveries(expiryCutoff);
        terminateExhaustedQueuedDeliveries();
    }

    @Transactional
    public List<PushDeliveryClaim> claimBatch(String claimOwner, Instant now) {
        requireClaimOwner(claimOwner);
        Timestamp nowTimestamp = Timestamp.from(now);
        Timestamp claimDeadline = Timestamp.from(now.minus(properties.claimTimeout()));
        Timestamp expiryCutoff = Timestamp.from(now.minus(properties.messageMaxAge()));

        List<PushDeliveryRow> available = pushDeliveryMapper.lockAvailableDeliveries(
                nowTimestamp,
                claimDeadline,
                properties.maxAttempts(),
                expiryCutoff,
                properties.batchSize()
        );

        List<PushDeliveryClaim> claimed = new ArrayList<>(available.size());
        for (PushDeliveryRow row : available) {
            PushDeliveryClaim delivery = toClaim(row, claimOwner);
            int changed = pushDeliveryMapper.claimDelivery(
                    nowTimestamp,
                    claimOwner,
                    delivery.deliveryId(),
                    claimDeadline
            );
            if (changed == 1) {
                claimed.add(delivery);
            }
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public boolean complete(PushDeliveryClaim delivery, PushSendResult result, Instant now) {
        return switch (result.outcome()) {
            case SUCCESS -> markDelivered(delivery, result, now);
            case RETRYABLE -> markRetryOrDead(delivery, result, now);
            case PERMANENT -> markPermanentFailure(delivery, result);
            case INVALID_RECIPIENT -> markInvalidRecipient(delivery, result);
        };
    }

    private void terminateDisabledRecipients() {
        pushDeliveryMapper.terminateDisabledRecipients(RECIPIENT_DISABLED);
    }

    private void expireStaleClaims(Timestamp claimDeadline, Timestamp expiryCutoff) {
        pushDeliveryMapper.expireStaleClaims(MESSAGE_EXPIRED, claimDeadline, expiryCutoff);
    }

    private void terminateExhaustedStaleClaims(Timestamp claimDeadline) {
        pushDeliveryMapper.terminateExhaustedStaleClaims(
                CLAIM_TIMEOUT_MAX_ATTEMPTS,
                claimDeadline,
                properties.maxAttempts()
        );
    }

    private void expireQueuedDeliveries(Timestamp expiryCutoff) {
        pushDeliveryMapper.expireQueuedDeliveries(MESSAGE_EXPIRED, expiryCutoff);
    }

    private void terminateExhaustedQueuedDeliveries() {
        pushDeliveryMapper.terminateExhaustedQueuedDeliveries(
                MAX_ATTEMPTS,
                properties.maxAttempts()
        );
    }

    private boolean markDelivered(
            PushDeliveryClaim delivery,
            PushSendResult result,
            Instant now
    ) {
        return pushDeliveryMapper.markDelivered(
                delivery.deliveryId(),
                delivery.claimOwner(),
                Timestamp.from(now),
                abbreviate(result.providerMessageId(), 512)
        ) == 1;
    }

    private boolean markRetryOrDead(
            PushDeliveryClaim delivery,
            PushSendResult result,
            Instant now
    ) {
        boolean exhausted = delivery.attemptCount() >= properties.maxAttempts();
        Timestamp nextAttemptAt = Timestamp.from(
                exhausted ? now : now.plus(backoffForAttempt(delivery.attemptCount()))
        );
        return pushDeliveryMapper.markRetryOrDead(
                delivery.deliveryId(),
                delivery.claimOwner(),
                exhausted ? "dead" : "retry",
                nextAttemptAt,
                errorValue(delivery, result)
        ) == 1;
    }

    private boolean markPermanentFailure(
            PushDeliveryClaim delivery,
            PushSendResult result
    ) {
        return pushDeliveryMapper.markPermanentFailure(
                delivery.deliveryId(),
                delivery.claimOwner(),
                errorValue(delivery, result)
        ) == 1;
    }

    private boolean markInvalidRecipient(
            PushDeliveryClaim delivery,
            PushSendResult result
    ) {
        String errorValue = errorValue(delivery, result);
        int changed = pushDeliveryMapper.markPermanentFailure(
                delivery.deliveryId(),
                delivery.claimOwner(),
                errorValue
        );
        if (changed != 1) {
            return false;
        }
        pushDeliveryMapper.disableDevice(delivery.deviceTokenId());
        pushDeliveryMapper.terminateOtherDeliveriesForInvalidDevice(
                delivery.deviceTokenId(),
                delivery.deliveryId(),
                delivery.claimOwner(),
                errorValue
        );
        return true;
    }

    private static PushDeliveryClaim toClaim(PushDeliveryRow row, String claimOwner) {
        return new PushDeliveryClaim(
                row.deliveryId(),
                row.deviceTokenId(),
                PushProvider.fromValue(row.pushProvider()),
                row.recipient(),
                PushRecipientType.fromValue(row.recipientType()),
                row.notificationId(),
                row.type(),
                row.body(),
                row.actorUserId(),
                row.contentId(),
                row.commentId(),
                row.attemptCount() + 1,
                claimOwner
        );
    }

    private Duration backoffForAttempt(int attemptCount) {
        long baseMillis = properties.baseBackoff().toMillis();
        long maxMillis = properties.maxBackoff().toMillis();
        int exponent = Math.max(0, Math.min(30, attemptCount - 1));
        long multiplier = 1L << exponent;
        long delayMillis;
        try {
            delayMillis = Math.multiplyExact(baseMillis, multiplier);
        } catch (ArithmeticException ignored) {
            delayMillis = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(maxMillis, delayMillis));
    }

    private static String errorValue(PushDeliveryClaim delivery, PushSendResult result) {
        return delivery.provider().errorPrefix() + "_" + result.errorCode().name();
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void requireClaimOwner(String claimOwner) {
        if (claimOwner == null || claimOwner.isBlank() || claimOwner.length() > 100) {
            throw new IllegalArgumentException("claimOwner must contain 1 to 100 characters.");
        }
    }
}
