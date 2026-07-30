package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SocialMapper {

    long countUserById(@Param("userId") String userId);

    String lockUser(@Param("userId") String userId);

    int insertFollowIfAbsent(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId,
            @Param("fanoutShard") int fanoutShard
    );

    int deleteFollow(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId
    );

    int deleteFeedEntries(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId
    );

    FollowPreferencesRow findPreferences(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId
    );

    int updatePreferences(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId,
            @Param("notifyNewContent") boolean notifyNewContent,
            @Param("muted") boolean muted
    );

    List<RelationRow> findRelations(
            @Param("userId") String userId,
            @Param("viewerId") String viewerId,
            @Param("keywordPattern") String keywordPattern,
            @Param("followerList") boolean followerList,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    long countRelations(
            @Param("userId") String userId,
            @Param("keywordPattern") String keywordPattern,
            @Param("followerList") boolean followerList
    );

    long countFollow(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId
    );

    Long findFollowerCount(@Param("userId") String userId);

    Long findFollowingCount(@Param("userId") String userId);

    int insertSocialStatsIfAbsent(@Param("userId") String userId);

    int incrementFollowingCount(@Param("userId") String userId);

    int incrementFollowerCount(@Param("userId") String userId);

    int decrementFollowingCount(@Param("userId") String userId);

    int decrementFollowerCount(@Param("userId") String userId);

    int backfillFeed(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId,
            @Param("limit") int limit
    );

    record FollowPreferencesRow(
            String followingId,
            boolean notifyNewContent,
            boolean muted
    ) {
    }

    record RelationRow(
            String id,
            String handle,
            String nickname,
            String avatarUrl,
            String bio,
            String location,
            long followerCount,
            long followingCount,
            boolean viewerFollowing,
            boolean viewerFollowedBy
    ) {
    }
}
