package com.github.hgdcoder.flowershow.push;

import java.util.Locale;

public enum PushProvider {
    FCM("fcm"),
    HUAWEI("huawei"),
    XIAOMI("xiaomi"),
    OPPO("oppo"),
    VIVO("vivo");

    private final String value;

    PushProvider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public String errorPrefix() {
        return name();
    }

    public static PushProvider fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Push provider is required.");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
