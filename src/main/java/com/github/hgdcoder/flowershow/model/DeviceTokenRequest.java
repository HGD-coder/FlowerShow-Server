package com.github.hgdcoder.flowershow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.util.Locale;

public record DeviceTokenRequest(
        @NotBlank @Size(max = 512) String token,
        @NotBlank @Pattern(regexp = "android|ios|web") String platform,
        @Size(max = 100) String deviceName,
        @NotBlank @Pattern(regexp = "token|fid") String recipientType,
        @NotBlank @Pattern(regexp = "fcm|huawei|xiaomi|oppo|vivo") String pushProvider,
        @Size(max = 100) String deviceBrand,
        @Size(max = 255) String appPackage
) {
    public DeviceTokenRequest {
        token = trim(token);
        platform = lowerCase(platform);
        deviceName = optionalText(deviceName);
        if (recipientType == null) {
            recipientType = "token";
        } else {
            recipientType = lowerCase(recipientType);
        }
        if (pushProvider == null) {
            pushProvider = "fcm";
        } else {
            pushProvider = lowerCase(pushProvider);
        }
        deviceBrand = optionalText(deviceBrand);
        appPackage = optionalText(appPackage);
    }

    @JsonIgnore
    @AssertTrue(message = "recipientType=fid is only supported by pushProvider=fcm")
    public boolean isRecipientTypeSupportedByProvider() {
        return !"fid".equals(recipientType) || "fcm".equals(pushProvider);
    }

    private static String lowerCase(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String optionalText(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
