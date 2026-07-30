package com.github.hgdcoder.flowershow.push;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PushDeliveryMessage(
        PushProvider provider,
        String recipient,
        PushRecipientType recipientType,
        String notificationId,
        String type,
        String title,
        String body,
        String actorUserId,
        String contentId,
        String commentId,
        String androidChannelId
) {
    public PushDeliveryMessage {
        Objects.requireNonNull(provider, "provider");
        requireText(recipient, "recipient");
        Objects.requireNonNull(recipientType, "recipientType");
        requireText(notificationId, "notificationId");
        requireText(type, "type");
        requireText(title, "title");
        requireText(body, "body");
        requireText(androidChannelId, "androidChannelId");
    }

    public Map<String, String> data() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("notificationId", notificationId);
        data.put("type", type);
        putIfPresent(data, "actorUserId", actorUserId);
        putIfPresent(data, "contentId", contentId);
        putIfPresent(data, "commentId", commentId);
        return Map.copyOf(data);
    }

    @Override
    public String toString() {
        return "PushDeliveryMessage[notificationId=" + notificationId
                + ", type=" + type
                + ", provider=" + provider
                + ", recipientType=" + recipientType
                + "]";
    }

    private static void putIfPresent(Map<String, String> data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
    }
}
