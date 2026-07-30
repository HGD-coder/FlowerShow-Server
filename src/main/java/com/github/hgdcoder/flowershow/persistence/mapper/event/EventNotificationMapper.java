package com.github.hgdcoder.flowershow.persistence.mapper.event;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface EventNotificationMapper {

    int countByReceiverAndDedupeKey(
            @Param("receiverUserId") String receiverUserId,
            @Param("dedupeKey") String dedupeKey
    );

    int insertNotification(NotificationInsert notification);

    int incrementUnreadCount(@Param("receiverUserId") String receiverUserId);

    int insertUnreadCountIfAbsent(@Param("receiverUserId") String receiverUserId);

    List<String> findEnabledDeviceIds(@Param("receiverUserId") String receiverUserId);

    int insertDeliveryIfAbsent(
            @Param("deliveryId") String deliveryId,
            @Param("notificationId") String notificationId,
            @Param("deviceId") String deviceId
    );
}
