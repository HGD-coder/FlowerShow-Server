package com.github.hgdcoder.flowershow.chat;

import com.github.hgdcoder.flowershow.chat.ChatModels.AddGroupMembersRequest;
import com.github.hgdcoder.flowershow.chat.ChatModels.ChatMessageDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.ConversationDetailDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.ConversationSummaryDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.CreateDirectConversationRequest;
import com.github.hgdcoder.flowershow.chat.ChatModels.CreateGroupConversationRequest;
import com.github.hgdcoder.flowershow.chat.ChatModels.MarkReadRequest;
import com.github.hgdcoder.flowershow.chat.ChatModels.ReadReceiptDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.RenameGroupRequest;
import com.github.hgdcoder.flowershow.chat.ChatModels.SendMessageRequest;
import com.github.hgdcoder.flowershow.chat.ChatModels.TransferGroupOwnerRequest;
import com.github.hgdcoder.flowershow.model.CursorPageResponse;
import com.github.hgdcoder.flowershow.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations")
    public CursorPageResponse<ConversationSummaryDto> conversations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize
    ) {
        return chatService.conversations(AuthenticatedUser.userId(jwt), cursor, pageSize);
    }

    @PostMapping("/conversations/direct")
    public ConversationDetailDto createDirect(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateDirectConversationRequest request
    ) {
        return chatService.createDirect(AuthenticatedUser.userId(jwt), request.userId());
    }

    @PostMapping("/conversations/group")
    public ConversationDetailDto createGroup(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateGroupConversationRequest request
    ) {
        return chatService.createGroup(
                AuthenticatedUser.userId(jwt),
                request.name(),
                request.memberUserIds()
        );
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetailDto conversation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId
    ) {
        return chatService.conversation(
                AuthenticatedUser.userId(jwt),
                conversationId
        );
    }

    @PatchMapping("/conversations/{conversationId}")
    public ConversationDetailDto renameGroup(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody RenameGroupRequest request
    ) {
        return chatService.renameGroup(
                AuthenticatedUser.userId(jwt),
                conversationId,
                request.name()
        );
    }

    @PostMapping("/conversations/{conversationId}/members")
    public ConversationDetailDto addMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody AddGroupMembersRequest request
    ) {
        return chatService.addMembers(
                AuthenticatedUser.userId(jwt),
                conversationId,
                request.userIds()
        );
    }

    @PostMapping("/conversations/{conversationId}/owner")
    public ConversationDetailDto transferOwner(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody TransferGroupOwnerRequest request
    ) {
        return chatService.transferOwner(
                AuthenticatedUser.userId(jwt),
                conversationId,
                request.userId()
        );
    }

    @PostMapping("/conversations/{conversationId}/dissolve")
    public ConversationDetailDto dissolve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId
    ) {
        return chatService.dissolve(
                AuthenticatedUser.userId(jwt),
                conversationId
        );
    }

    @DeleteMapping("/conversations/{conversationId}/members/{userId}")
    public ConversationDetailDto removeMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @PathVariable String userId
    ) {
        return chatService.removeMember(
                AuthenticatedUser.userId(jwt),
                conversationId,
                userId
        );
    }

    @PostMapping("/conversations/{conversationId}/leave")
    public Map<String, Boolean> leave(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId
    ) {
        return Map.of(
                "changed",
                chatService.leave(AuthenticatedUser.userId(jwt), conversationId)
        );
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public CursorPageResponse<ChatMessageDto> messages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize
    ) {
        return chatService.messages(
                AuthenticatedUser.userId(jwt),
                conversationId,
                cursor,
                pageSize
        );
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ChatMessageDto send(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return chatService.send(
                AuthenticatedUser.userId(jwt),
                conversationId,
                request
        );
    }

    @PostMapping("/conversations/{conversationId}/read")
    public ReadReceiptDto markRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody(required = false) MarkReadRequest request
    ) {
        return chatService.markRead(
                AuthenticatedUser.userId(jwt),
                conversationId,
                request == null ? null : request.messageId()
        );
    }
}
