package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProfileMapper {

    ProfileRow findProfileById(@Param("userId") String userId);

    ContentDisplayRow findContentDisplayById(@Param("contentId") String contentId);

    long countFollow(
            @Param("followerId") String followerId,
            @Param("followingId") String followingId
    );

    long countReceivedLikesForOwner(@Param("userId") String userId);

    long countReceivedLikesForPublicProfile(@Param("userId") String userId);

    Long findFollowingCount(@Param("userId") String userId);

    Long findFollowerCount(@Param("userId") String userId);

    int updateProfile(
            @Param("userId") String userId,
            @Param("handle") String handle,
            @Param("nickname") String nickname,
            @Param("avatarUrl") String avatarUrl,
            @Param("profileBannerUrl") String profileBannerUrl,
            @Param("bio") String bio,
            @Param("location") String location,
            @Param("profileVisibility") String profileVisibility,
            @Param("showLikedOnProfile") boolean showLikedOnProfile,
            @Param("showFavoritesOnProfile") boolean showFavoritesOnProfile
    );

    int updateContentDisplay(
            @Param("contentId") String contentId,
            @Param("showOnProfile") boolean showOnProfile,
            @Param("pinnedAt") Timestamp pinnedAt,
            @Param("sortOrder") int sortOrder
    );

    record ProfileRow(
            String id,
            String handle,
            String nickname,
            String avatarUrl,
            String profileBannerUrl,
            String bio,
            String location,
            String source,
            String profileVisibility,
            boolean showLikedOnProfile,
            boolean showFavoritesOnProfile,
            Timestamp createdAt,
            Timestamp updatedAt
    ) {
    }

    record ContentDisplayRow(
            String authorUserId,
            boolean showOnProfile,
            Timestamp pinnedAt,
            int sortOrder
    ) {
    }
}
