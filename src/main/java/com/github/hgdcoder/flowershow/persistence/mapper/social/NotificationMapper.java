package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationMapper {

    List<NotificationRow> findPage(
            @Param("userId") String userId,
            @Param("cursorCreatedAt") Timestamp cursorCreatedAt,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit
    );

    Long findUnreadCount(@Param("userId") String userId);

    int markRead(
            @Param("userId") String userId,
            @Param("notificationId") String notificationId,
            @Param("readAt") Timestamp readAt
    );

    int decrementUnreadCount(@Param("userId") String userId);

    int markAllRead(@Param("userId") String userId);

    int decrementUnreadCountBy(
            @Param("userId") String userId,
            @Param("delta") int delta
    );

    record NotificationRow(
            String id,
            String receiverUserId,
            String actorUserId,
            String actorNickname,
            String actorAvatarUrl,
            String type,
            String contentId,
            String commentId,
            String message,
            Timestamp readAt,
            Timestamp createdAt
    ) {
    }
}
