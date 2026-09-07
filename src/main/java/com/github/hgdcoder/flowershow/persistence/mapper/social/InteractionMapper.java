package com.github.hgdcoder.flowershow.persistence.mapper.social;

import com.github.hgdcoder.flowershow.model.ContentStatsDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InteractionMapper {

    long countContentById(@Param("contentId") String contentId);

    long countPublishedPublicContent(@Param("contentId") String contentId);

    long countUserById(@Param("userId") String userId);

    int insertContentLike(
            @Param("contentId") String contentId,
            @Param("userId") String userId
    );

    int deleteContentLike(
            @Param("contentId") String contentId,
            @Param("userId") String userId
    );

    int incrementContentLikeCount(@Param("contentId") String contentId);

    int decrementContentLikeCount(@Param("contentId") String contentId);

    String lockUser(@Param("userId") String userId);

    long countDefaultFavorite(
            @Param("contentId") String contentId,
            @Param("userId") String userId
    );

    String findDefaultCollectionId(@Param("userId") String userId);

    int insertDefaultCollection(
            @Param("collectionId") String collectionId,
            @Param("userId") String userId
    );

    int insertCollectionItem(
            @Param("collectionId") String collectionId,
            @Param("contentId") String contentId
    );

    int deleteDefaultCollectionItem(
            @Param("contentId") String contentId,
            @Param("userId") String userId
    );

    int incrementContentFavoriteCount(@Param("contentId") String contentId);

    int decrementContentFavoriteCount(@Param("contentId") String contentId);

    CommentRef findCommentRef(@Param("commentId") String commentId);

    int insertCommentLike(
            @Param("commentId") String commentId,
            @Param("userId") String userId
    );

    int incrementCommentLikeCount(@Param("commentId") String commentId);

    ContentStatsDto findContentStats(@Param("contentId") String contentId);

    record CommentRef(String id, String contentId) {
    }
}
