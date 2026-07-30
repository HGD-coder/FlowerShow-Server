package com.github.hgdcoder.flowershow.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class ChatModels {

    private ChatModels() {
    }

    public record CreateDirectConversationRequest(
            @NotBlank @Size(max = 64) String userId
    ) {
    }

    public record CreateGroupConversationRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 199) List<@NotBlank @Size(max = 64) String> memberUserIds
    ) {
    }

    public record RenameGroupRequest(
            @NotBlank @Size(max = 120) String name
    ) {
    }

    public record TransferGroupOwnerRequest(
            @NotBlank @Size(max = 64) String userId
    ) {
    }

    public record AddGroupMembersRequest(
            @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 64) String> userIds
    ) {
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 20) String type,
            @Size(max = 4000) String text,
            @Size(max = 64) String contentId,
            @NotBlank @Size(max = 128) String clientMessageId
    ) {
    }

    public record MarkReadRequest(
            @Size(max = 64) String messageId
    ) {
    }

    public record ChatMemberDto(
            String userId,
            String nickname,
            String avatarUrl,
            String role,
            String joinedAt
    ) {
    }

    public record AvatarMemberDto(
            String userId,
            String nickname,
            String avatarUrl
    ) {
    }

    public record SharedContentDto(
            String id,
            String title,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String coverUrl,
            String authorUserId,
            String authorNickname
    ) {
    }

    public record ChatMessageDto(
            String id,
            String conversationId,
            String senderUserId,
            String senderNickname,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String senderAvatarUrl,
            String type,
            String text,
            String sharedContentId,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            SharedContentDto sharedContent,
            String clientMessageId,
            String createdAt
    ) {
    }

    public record ConversationSummaryDto(
            String id,
            String type,
            String name,
            String displayName,
            String displayAvatarUrl,
            List<AvatarMemberDto> avatarMembers,
            String ownerUserId,
            String state,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String dissolvedAt,
            ChatMessageDto lastMessage,
            long unreadCount,
            String updatedAt
    ) {
    }

    public record ConversationDetailDto(
            String id,
            String type,
            String name,
            String ownerUserId,
            String state,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String dissolvedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String dissolvedByUserId,
            List<ChatMemberDto> members,
            String createdAt,
            String updatedAt
    ) {
    }

    public record ReadReceiptDto(
            String conversationId,
            String lastReadMessageId,
            String readAt,
            long unreadCount
    ) {
    }
}
