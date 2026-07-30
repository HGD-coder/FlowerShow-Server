package com.github.hgdcoder.flowershow.persistence.mapper.content;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FollowingFeedMapper {

    List<FollowingFeedKey> findFeedKeys(
            @Param("userId") String userId,
            @Param("fanoutOnWriteMaxFollowers") long fanoutOnWriteMaxFollowers,
            @Param("cursorSortValue") Long cursorSortValue,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit
    );

    long countUserById(@Param("userId") String userId);
}
