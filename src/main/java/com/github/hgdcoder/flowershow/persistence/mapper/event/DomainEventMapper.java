package com.github.hgdcoder.flowershow.persistence.mapper.event;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DomainEventMapper {

    List<String> findActiveConversationMemberUserIds(
            @Param("conversationId") String conversationId
    );

    String findCommentAuthorUserId(@Param("commentId") String commentId);

    String findContentAuthorUserId(@Param("contentId") String contentId);
}
