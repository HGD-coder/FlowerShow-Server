package com.github.hgdcoder.flowershow.persistence.mapper.event;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Param;

public interface ProcessedEventMapper {

    int countProcessed(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId
    );

    int insertProcessed(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId
    );

    int deleteOlderThan(@Param("cutoff") Timestamp cutoff);
}
