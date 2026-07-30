package com.github.hgdcoder.flowershow.persistence.mapper.chat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ChatMapper {

    String findDirectConversationId(
            @Param("firstUserId") String firstUserId,
            @Param("secondUserId") String secondUserId
    );

    int insertDirectConversation(
            @Param("conversationId") String conversationId,
            @Param("firstUserId") String firstUserId,
            @Param("secondUserId") String secondUserId
    );

    int insertGroupConversation(
            @Param("conversationId") String conversationId,
            @Param("name") String name,
            @Param("ownerUserId") String ownerUserId
    );

    List<ChatConversationSummaryRow> findConversationSummaries(
            @Param("userId") String userId,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit
    );

    List<ChatAvatarMemberRow> findAvatarMembersByConversationIds(
            @Param("conversationIds") Collection<String> conversationIds
    );

    int renameGroup(
            @Param("conversationId") String conversationId,
            @Param("name") String name
    );

    int updateMembershipRolesForTransfer(
            @Param("conversationId") String conversationId,
            @Param("previousOwnerUserId") String previousOwnerUserId,
            @Param("userIds") Collection<String> userIds
    );

    int updateConversationOwner(
            @Param("conversationId") String conversationId,
            @Param("ownerUserId") String ownerUserId
    );

    int dissolveConversation(
            @Param("conversationId") String conversationId,
            @Param("actorUserId") String actorUserId
    );

    int removeMember(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId
    );

    int leaveMember(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId
    );

    List<ChatMessageRow> findMessages(
            @Param("conversationId") String conversationId,
            @Param("cursorSequence") Long cursorSequence,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit
    );

    int insertMessage(
            @Param("messageId") String messageId,
            @Param("conversationId") String conversationId,
            @Param("senderUserId") String senderUserId,
            @Param("messageType") String messageType,
            @Param("body") String body,
            @Param("sharedContentId") String sharedContentId,
            @Param("clientMessageId") String clientMessageId
    );

    int updateConversationLastMessage(
            @Param("conversationId") String conversationId,
            @Param("messageId") String messageId,
            @Param("sequence") long sequence,
            @Param("createdAt") Instant createdAt
    );

    Long findMessageSequence(
            @Param("conversationId") String conversationId,
            @Param("messageId") String messageId
    );

    int updateReadReceipt(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId,
            @Param("readSequence") long readSequence
    );

    String findMessageIdBySequence(
            @Param("conversationId") String conversationId,
            @Param("sequence") long sequence
    );

    List<ChatMemberDetailRow> findActiveMembers(
            @Param("conversationId") String conversationId
    );

    ChatConversationRow findConversation(
            @Param("conversationId") String conversationId,
            @Param("forUpdate") boolean forUpdate
    );

    ChatMembershipRow findMembership(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId,
            @Param("forUpdate") boolean forUpdate
    );

    int activateMember(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId,
            @Param("lastReadSequence") long lastReadSequence
    );

    List<ChatMemberStateRow> findMemberStates(
            @Param("conversationId") String conversationId,
            @Param("userIds") Collection<String> userIds
    );

    long countActiveMembers(@Param("conversationId") String conversationId);

    int insertMember(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId,
            @Param("role") String role,
            @Param("lastReadSequence") long lastReadSequence
    );

    ChatMessageRow findByClientMessageId(
            @Param("conversationId") String conversationId,
            @Param("senderUserId") String senderUserId,
            @Param("clientMessageId") String clientMessageId
    );

    ChatMessageRow findMessageById(@Param("messageId") String messageId);

    int countShareableVideo(@Param("contentId") String contentId);

    int countMutualFollows(
            @Param("firstUserId") String firstUserId,
            @Param("secondUserId") String secondUserId
    );

    String lockUser(@Param("userId") String userId);

    long countUnreadMessages(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId,
            @Param("readSequence") long readSequence
    );
}
