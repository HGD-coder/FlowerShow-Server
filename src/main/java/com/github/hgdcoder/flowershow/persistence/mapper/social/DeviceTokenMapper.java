package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeviceTokenMapper {

    ExistingDevice findExisting(
            @Param("pushProvider") String pushProvider,
            @Param("token") String token
    );

    int insertDevice(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("token") String token,
            @Param("platform") String platform,
            @Param("deviceName") String deviceName,
            @Param("recipientType") String recipientType,
            @Param("pushProvider") String pushProvider,
            @Param("deviceBrand") String deviceBrand,
            @Param("appPackage") String appPackage
    );

    int deletePendingDeliveries(@Param("deviceId") String deviceId);

    int updateDevice(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("platform") String platform,
            @Param("deviceName") String deviceName,
            @Param("recipientType") String recipientType,
            @Param("deviceBrand") String deviceBrand,
            @Param("appPackage") String appPackage
    );

    List<DeviceTokenRow> findByUserId(@Param("userId") String userId);

    int disable(
            @Param("userId") String userId,
            @Param("deviceId") String deviceId
    );

    DeviceTokenRow findById(
            @Param("userId") String userId,
            @Param("id") String id
    );

    record ExistingDevice(String id, String userId) {
    }

    record DeviceTokenRow(
            String id,
            String platform,
            String deviceName,
            String recipientType,
            String pushProvider,
            String deviceBrand,
            String appPackage,
            boolean enabled,
            Timestamp lastSeenAt
    ) {
    }
}
