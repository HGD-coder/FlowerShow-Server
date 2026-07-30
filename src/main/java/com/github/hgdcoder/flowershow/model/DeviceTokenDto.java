package com.github.hgdcoder.flowershow.model;

public record DeviceTokenDto(
        String id,
        String platform,
        String deviceName,
        String recipientType,
        String pushProvider,
        String deviceBrand,
        String appPackage,
        boolean enabled,
        String lastSeenAt
) {
}
