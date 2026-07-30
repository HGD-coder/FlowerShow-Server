package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.CursorPageResponse;
import com.github.hgdcoder.flowershow.model.NotificationDto;
import com.github.hgdcoder.flowershow.persistence.mapper.social.NotificationMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.NotificationMapper.NotificationRow;
import com.github.hgdcoder.flowershow.util.CursorCodec;
import com.github.hgdcoder.flowershow.util.CursorCodec.Cursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    public List<NotificationDto> list(String userId) {
        return page(userId, null, 50).items();
    }

    public CursorPageResponse<NotificationDto> page(String userId, String cursorValue, int pageSize) {
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        Cursor cursor = CursorCodec.decode(cursorValue);
        Timestamp cursorTimestamp = cursor == null
                ? null
                : Timestamp.from(fromEpochMicros(cursor.sortValue()));
        String cursorId = cursor == null ? null : cursor.id();
        List<NotificationDto> rows = notificationMapper.findPage(
                        userId,
                        cursorTimestamp,
                        cursorId,
                        safePageSize + 1
                ).stream()
                .map(NotificationService::toDto)
                .toList();
        boolean hasMore = rows.size() > safePageSize;
        List<NotificationDto> items = hasMore ? rows.subList(0, safePageSize) : rows;
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            NotificationDto last = items.get(items.size() - 1);
            nextCursor = CursorCodec.encode(toEpochMicros(Instant.parse(last.createdAt())), last.id());
        }
        return new CursorPageResponse<>(List.copyOf(items), nextCursor, hasMore);
    }

    public long unreadCount(String userId) {
        Long value = notificationMapper.findUnreadCount(userId);
        return value == null ? 0 : value;
    }

    @Transactional
    public boolean markRead(String userId, String notificationId) {
        boolean changed = notificationMapper.markRead(
                userId,
                notificationId,
                Timestamp.from(Instant.now())
        ) > 0;
        if (changed) {
            notificationMapper.decrementUnreadCount(userId);
        }
        return changed;
    }

    @Transactional
    public int markAllRead(String userId) {
        int changed = notificationMapper.markAllRead(userId);
        notificationMapper.resetUnreadCount(userId);
        return changed;
    }

    private static String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L
        );
    }

    private static Instant fromEpochMicros(long epochMicros) {
        long seconds = Math.floorDiv(epochMicros, 1_000_000L);
        long micros = Math.floorMod(epochMicros, 1_000_000L);
        return Instant.ofEpochSecond(seconds, micros * 1_000L);
    }

    private static NotificationDto toDto(NotificationRow row) {
        return new NotificationDto(
                row.id(),
                row.receiverUserId(),
                row.actorUserId(),
                row.actorNickname(),
                row.actorAvatarUrl(),
                row.type(),
                row.contentId(),
                row.commentId(),
                row.message(),
                row.readAt() != null,
                toIso(row.createdAt())
        );
    }
}
