package com.github.hgdcoder.flowershow.persistence.mapper.event;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PushDeliveryMapper {

    List<PushDeliveryRow> lockAvailableDeliveries(
            @Param("now") Timestamp now,
            @Param("claimDeadline") Timestamp claimDeadline,
            @Param("maxAttempts") int maxAttempts,
            @Param("expiryCutoff") Timestamp expiryCutoff,
            @Param("batchSize") int batchSize
    );

    int claimDelivery(
            @Param("claimedAt") Timestamp claimedAt,
            @Param("claimOwner") String claimOwner,
            @Param("deliveryId") String deliveryId,
            @Param("claimDeadline") Timestamp claimDeadline
    );

    int terminateDisabledRecipients(@Param("lastError") String lastError);

    int expireStaleClaims(
            @Param("lastError") String lastError,
            @Param("claimDeadline") Timestamp claimDeadline,
            @Param("expiryCutoff") Timestamp expiryCutoff
    );

    int terminateExhaustedStaleClaims(
            @Param("lastError") String lastError,
            @Param("claimDeadline") Timestamp claimDeadline,
            @Param("maxAttempts") int maxAttempts
    );

    int expireQueuedDeliveries(
            @Param("lastError") String lastError,
            @Param("expiryCutoff") Timestamp expiryCutoff
    );

    int terminateExhaustedQueuedDeliveries(
            @Param("lastError") String lastError,
            @Param("maxAttempts") int maxAttempts
    );

    int markDelivered(
            @Param("deliveryId") String deliveryId,
            @Param("claimOwner") String claimOwner,
            @Param("deliveredAt") Timestamp deliveredAt,
            @Param("providerMessageId") String providerMessageId
    );

    int markRetryOrDead(
            @Param("deliveryId") String deliveryId,
            @Param("claimOwner") String claimOwner,
            @Param("status") String status,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("lastError") String lastError
    );

    int markPermanentFailure(
            @Param("deliveryId") String deliveryId,
            @Param("claimOwner") String claimOwner,
            @Param("lastError") String lastError
    );

    int disableDevice(@Param("deviceTokenId") String deviceTokenId);

    int terminateOtherDeliveriesForInvalidDevice(
            @Param("deviceTokenId") String deviceTokenId,
            @Param("deliveryId") String deliveryId,
            @Param("claimOwner") String claimOwner,
            @Param("lastError") String lastError
    );
}
