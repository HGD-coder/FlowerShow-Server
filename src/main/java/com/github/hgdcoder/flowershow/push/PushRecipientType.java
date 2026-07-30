package com.github.hgdcoder.flowershow.push;

import java.util.Locale;

public enum PushRecipientType {
    TOKEN("token"),
    FID("fid");

    private final String value;

    PushRecipientType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PushRecipientType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Push recipient type is required.");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
