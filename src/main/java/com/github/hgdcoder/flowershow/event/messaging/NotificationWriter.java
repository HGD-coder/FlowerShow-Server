package com.github.hgdcoder.flowershow.event.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.event.EventNotificationMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.event.NotificationInsert;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NotificationWriter {

    private final EventNotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;

    public NotificationWriter(EventNotificationMapper notificationMapper, ObjectMapper objectMapper) {
        this.notificationMapper = notificationMapper;
        this.objectMapper = objectMapper;
    }

    public boolean insert(
            String eventId,
            String receiverUserId,
            String actorUserId,
            String type,
            String contentId,
            String commentId,
            String message,
            Map<String, Object> payload
    ) {
        if (receiverUserId == null || receiverUserId.isBlank() || receiverUserId.equals(actorUserId)) {
            return false;
        }

        String dedupeKey = eventId + ":" + type;
        if (notificationMapper.countByReceiverAndDedupeKey(receiverUserId, dedupeKey) > 0) {
            return false;
        }

        String notificationId = deterministicId("ntf_", eventId + ":" + type + ":" + receiverUserId);
        String payloadJson = serialize(payload);
        notificationMapper.insertNotification(new NotificationInsert(
                notificationId,
                receiverUserId,
                actorUserId,
                type,
                contentId,
                commentId,
                message,
                eventId,
                dedupeKey,
                payloadJson
        ));

        int updated = notificationMapper.incrementUnreadCount(receiverUserId);
        if (updated == 0) {
            notificationMapper.insertUnreadCountIfAbsent(receiverUserId);
        }

        enqueueDeviceDeliveries(notificationId, receiverUserId);
        return true;
    }

    private void enqueueDeviceDeliveries(String notificationId, String receiverUserId) {
        List<String> deviceIds = notificationMapper.findEnabledDeviceIds(receiverUserId);
        for (String deviceId : deviceIds) {
            String deliveryId = deterministicId("ndl_", notificationId + ":" + deviceId);
            notificationMapper.insertDeliveryIfAbsent(deliveryId, notificationId, deviceId);
        }
    }

    private String serialize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize notification payload.", e);
        }
    }

    private static String deterministicId(String prefix, String source) {
        UUID uuid = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return prefix + uuid.toString().replace("-", "");
    }
}
