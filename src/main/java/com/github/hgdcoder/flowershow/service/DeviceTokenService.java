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
            deviceTokenMapper.insertDevice(
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
        } else {
            id = existing.id();
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
        return findById(userId, id);
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
