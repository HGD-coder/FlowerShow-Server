package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.DeviceTokenDto;
import com.github.hgdcoder.flowershow.model.DeviceTokenRequest;
import com.github.hgdcoder.flowershow.persistence.mapper.social.DeviceTokenMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.DeviceTokenMapper.DeviceTokenRow;
import com.github.hgdcoder.flowershow.persistence.mapper.social.DeviceTokenMapper.ExistingDevice;
import com.github.hgdcoder.flowershow.push.PushProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTokenService {

    private final DeviceTokenMapper deviceTokenMapper;

    public DeviceTokenService(DeviceTokenMapper deviceTokenMapper) {
        this.deviceTokenMapper = deviceTokenMapper;
    }

    @Transactional
    public DeviceTokenDto register(String userId, DeviceTokenRequest request) {
        String token = request.token();
        String platform = request.platform();
        String deviceName = request.deviceName();
        String recipientType = request.recipientType();
        PushProvider pushProvider = PushProvider.fromValue(request.pushProvider());
        String deviceBrand = request.deviceBrand();
        String appPackage = request.appPackage();
        ExistingDevice existing = deviceTokenMapper.findExisting(pushProvider.value(), token);
        String id;
        if (existing == null) {
            id = deterministicId(pushProvider, token);
            int inserted = deviceTokenMapper.insertDevice(
                    id,
                    userId,
                    token,
                    platform,
                    deviceName,
                    recipientType,
                    pushProvider.value(),
                    deviceBrand,
                    appPackage
            );
            if (inserted == 0) {
                // A concurrent registration claimed the unique (push_provider, token)
                // slot; fall back to the update path instead of failing with 500.
                existing = deviceTokenMapper.findExisting(pushProvider.value(), token);
                if (existing == null) {
                    throw new IllegalStateException("Device token registration conflict could not be resolved.");
                }
                id = existing.id();
                updateExisting(existing, id, userId, platform, deviceName, recipientType, deviceBrand, appPackage);
            }
        } else {
            id = existing.id();
            updateExisting(existing, id, userId, platform, deviceName, recipientType, deviceBrand, appPackage);
        }
        return findById(userId, id);
    }

    private void updateExisting(
            ExistingDevice existing,
            String id,
            String userId,
            String platform,
            String deviceName,
            String recipientType,
            String deviceBrand,
            String appPackage
    ) {
        if (!userId.equals(existing.userId())) {
            deviceTokenMapper.deletePendingDeliveries(id);
        }
        deviceTokenMapper.updateDevice(
                id,
                userId,
                platform,
                deviceName,
                recipientType,
                deviceBrand,
                appPackage
        );
    }

    public List<DeviceTokenDto> list(String userId) {
        return deviceTokenMapper.findByUserId(userId).stream()
                .map(DeviceTokenService::toDto)
                .toList();
    }

    @Transactional
    public boolean disable(String userId, String deviceId) {
        return deviceTokenMapper.disable(userId, deviceId) > 0;
    }

    private DeviceTokenDto findById(String userId, String id) {
        DeviceTokenRow row = deviceTokenMapper.findById(userId, id);
        if (row == null) {
            throw new EmptyResultDataAccessException(1);
        }
        return toDto(row);
    }

    private static String deterministicId(PushProvider provider, String token) {
        String source = provider.value() + ":" + token;
        UUID uuid = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return "dev_" + uuid.toString().replace("-", "");
    }

    private static DeviceTokenDto toDto(DeviceTokenRow row) {
        return new DeviceTokenDto(
                row.id(),
                row.platform(),
                row.deviceName(),
                row.recipientType(),
                row.pushProvider(),
                row.deviceBrand(),
                row.appPackage(),
                row.enabled(),
                UserService.toIso(row.lastSeenAt())
        );
    }
}
