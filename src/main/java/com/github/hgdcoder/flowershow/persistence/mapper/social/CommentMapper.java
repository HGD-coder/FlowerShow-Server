package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommentMapper {

    long countContentById(@Param("contentId") String contentId);

    long countUserById(@Param("userId") String userId);

    long countParentInContent(
            @Param("parentId") String parentId,
            @Param("contentId") String contentId
    );

    List<CommentRow> findVisibleByContentId(@Param("contentId") String contentId);

    CommentRow findById(@Param("id") String id);

    int insertComment(
            @Param("id") String id,
            @Param("contentId") String contentId,
            @Param("userId") String userId,
            @Param("parentId") String parentId,
            @Param("body") String body
    );

    int incrementContentCommentCount(@Param("contentId") String contentId);

    record CommentRow(
            String id,
            String contentId,
            String userId,
            String nickname,
            String avatarUrl,
            String parentId,
            String body,
            int likeCount,
            Timestamp createdAt
    ) {
    }
}
