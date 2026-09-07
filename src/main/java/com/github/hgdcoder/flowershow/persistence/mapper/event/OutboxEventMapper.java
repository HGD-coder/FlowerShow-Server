package com.github.hgdcoder.flowershow.persistence.mapper.event;

import com.github.hgdcoder.flowershow.event.outbox.OutboxEventRecord;
import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OutboxEventMapper {

    int insertEvent(OutboxEventInsert event);

    List<OutboxEventRecord> lockNextPendingEvent();

    Integer findRetryCount(@Param("eventId") String eventId);

    int markProcessed(@Param("eventId") String eventId);

    int markRetry(
            @Param("eventId") String eventId,
            @Param("status") String status,
            @Param("retryCount") int retryCount,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("lastError") String lastError
    );

    int deleteProcessedOlderThan(@Param("cutoff") Timestamp cutoff);

    int deleteDeadOlderThan(@Param("cutoff") Timestamp cutoff);
}
