package com.github.hgdcoder.flowershow.persistence.mapper.event;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FanoutMapper {

    PublishedContentRow findPublishedContent(@Param("contentId") String contentId);

    List<FollowerPreferenceRow> findFollowerPage(
            @Param("authorUserId") String authorUserId,
            @Param("cursor") String cursor,
            @Param("notificationsOnly") boolean notificationsOnly,
            @Param("limit") int limit
    );

    int insertFeedEntry(
            @Param("userId") String userId,
            @Param("contentId") String contentId,
            @Param("authorUserId") String authorUserId,
            @Param("sourceEventId") String sourceEventId,
            @Param("score") long score
    );
}
