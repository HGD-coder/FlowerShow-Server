package com.github.hgdcoder.flowershow.chat;

import com.github.hgdcoder.flowershow.chat.ChatModels.AvatarMemberDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.ChatMemberDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.ChatMessageDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.ConversationDetailDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.ConversationSummaryDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.ReadReceiptDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.SendMessageRequest;
import com.github.hgdcoder.flowershow.chat.ChatModels.SharedContentDto;
import com.github.hgdcoder.flowershow.event.DomainEvent;
import com.github.hgdcoder.flowershow.event.EventPublisher;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.model.CursorPageResponse;
import com.github.hgdcoder.flowershow.persistence.mapper.chat.ChatAvatarMemberRow;
import com.github.hgdcoder.flowershow.persistence.mapper.chat.ChatConversationRow;
import com.github.hgdcoder.flowershow.persistence.mapper.chat.ChatConversationSummaryRow;
import com.github.hgdcoder.flowershow.persistence.mapper.chat.ChatMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.chat.ChatMemberStateRow;
import com.github.hgdcoder.flowershow.persistence.mapper.chat.ChatMembershipRow;
import com.github.hgdcoder.flowershow.persistence.mapper.chat.ChatMessageRow;
import com.github.hgdcoder.flowershow.util.CursorCodec;
import com.github.hgdcoder.flowershow.util.CursorCodec.Cursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

    private static final String DIRECT = "direct";
    private static final String GROUP = "group";
    private static final String ACTIVE = "active";
    private static final String DISSOLVED = "dissolved";
    private static final String TEXT = "text";
    private static final String VIDEO_SHARE = "video_share";
    private static final int MAX_MESSAGE_TYPE_LENGTH = 20;
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final int MAX_GROUP_MEMBERS = 200;
    private static final int MAX_GROUP_INVITEES = MAX_GROUP_MEMBERS - 1;
    private static final int MAX_ADD_MEMBERS_REQUEST = MAX_GROUP_MEMBERS;

    private final ChatMapper chatMapper;
    private final EventPublisher eventPublisher;

    public ChatService(ChatMapper chatMapper, EventPublisher eventPublisher) {
        this.chatMapper = chatMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ConversationDetailDto createDirect(String actorUserId, String otherUserIdValue) {
        String otherUserId = requiredId(otherUserIdValue, "userId");
        if (actorUserId.equals(otherUserId)) {
            throw error(HttpStatus.BAD_REQUEST, "Cannot create a direct conversation with yourself.");
        }
        List<String> users = sortedDistinct(List.of(actorUserId, otherUserId));
        lockUsers(users);
        requireMutualFollow(actorUserId, otherUserId);

        String firstUserId = users.get(0);
        String secondUserId = users.get(1);
        String existing = chatMapper.findDirectConversationId(firstUserId, secondUserId);
        String conversationId;
        if (existing == null) {
            conversationId = newId("con");
            chatMapper.insertDirectConversation(conversationId, firstUserId, secondUserId);
            insertMember(conversationId, firstUserId, "member", 0);
            insertMember(conversationId, secondUserId, "member", 0);
        } else {
            conversationId = existing;
        }
        requireActiveMember(conversationId, actorUserId, false);
        return detail(conversationId);
    }

    @Transactional
    public ConversationDetailDto createGroup(
            String ownerUserId,
            String nameValue,
            List<String> memberUserIds
    ) {
        String name = requiredName(nameValue);
        requireListSize(memberUserIds, MAX_GROUP_INVITEES, "memberUserIds");
        LinkedHashSet<String> invited = normalizedIds(memberUserIds, "memberUserIds");
        invited.remove(ownerUserId);
        if (invited.size() > MAX_GROUP_INVITEES) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "A group can contain at most " + MAX_GROUP_MEMBERS + " active members."
            );
        }
        List<String> usersToLock = new ArrayList<>(invited);
        usersToLock.add(ownerUserId);
        lockUsers(sortedDistinct(usersToLock));
        for (String invitedUserId : invited) {
            requireMutualFollow(ownerUserId, invitedUserId);
        }

        String conversationId = newId("con");
        chatMapper.insertGroupConversation(conversationId, name, ownerUserId);
        insertMember(conversationId, ownerUserId, "owner", 0);
        for (String invitedUserId : invited) {
            insertMember(conversationId, invitedUserId, "member", 0);
        }
        return detail(conversationId);
    }

    public CursorPageResponse<ConversationSummaryDto> conversations(
            String userId,
            String cursorValue,
            int pageSize
    ) {
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        Cursor cursor = CursorCodec.decode(cursorValue);
        Instant cursorAt = cursor == null ? null : fromEpochMicros(cursor.sortValue());
        String cursorId = cursor == null ? null : cursor.id();
        List<ChatConversationSummaryRow> rows = chatMapper.findConversationSummaries(
                userId,
                cursorAt,
                cursorId,
                safePageSize + 1
        );

        boolean hasMore = rows.size() > safePageSize;
        List<ChatConversationSummaryRow> page = hasMore
                ? rows.subList(0, safePageSize)
                : rows;
        Map<String, List<AvatarMemberDto>> avatarMembers = avatarMembers(page);
        List<ConversationSummaryDto> items = page.stream()
                .map(row -> conversationSummaryDto(
                        row,
                        avatarMembers.getOrDefault(row.id(), List.of())
                ))
                .toList();
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ChatConversationSummaryRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(toEpochMicros(last.activityAt()), last.id());
        }
        return new CursorPageResponse<>(items, nextCursor, hasMore);
    }

    private Map<String, List<AvatarMemberDto>> avatarMembers(
            List<ChatConversationSummaryRow> page
    ) {
        List<String> groupConversationIds = page.stream()
                .filter(row -> GROUP.equals(row.type()))
                .map(ChatConversationSummaryRow::id)
                .distinct()
                .toList();
        if (groupConversationIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<AvatarMemberDto>> values = new LinkedHashMap<>();
        for (ChatAvatarMemberRow row
                : chatMapper.findAvatarMembersByConversationIds(groupConversationIds)) {
            values.computeIfAbsent(row.conversationId(), ignored -> new ArrayList<>())
                    .add(new AvatarMemberDto(row.userId(), row.nickname(), row.avatarUrl()));
        }
        values.replaceAll((ignored, members) -> List.copyOf(members));
        return values;
    }

    public ConversationDetailDto conversation(String actorUserId, String conversationIdValue) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        conversationRow(conversationId, false);
        requireActiveMember(conversationId, actorUserId, false);
        return detail(conversationId);
    }

    @Transactional
    public ConversationDetailDto renameGroup(
            String actorUserId,
            String conversationIdValue,
            String nameValue
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        requireOwner(conversation, actorUserId);
        requireActiveConversation(conversation);
        chatMapper.renameGroup(conversationId, requiredName(nameValue));
        return detail(conversationId);
    }

    @Transactional
    public ConversationDetailDto addMembers(
            String actorUserId,
            String conversationIdValue,
            List<String> userIdValues
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        requireOwner(conversation, actorUserId);
        requireActiveConversation(conversation);
        requireListSize(userIdValues, MAX_ADD_MEMBERS_REQUEST, "userIds");
        LinkedHashSet<String> userIds = normalizedIds(userIdValues, "userIds");
        userIds.remove(conversation.ownerUserId());
        Map<String, String> memberStates = memberStates(conversation.id(), userIds);
        long activeMemberCount = activeMemberCount(conversation.id());
        long activationCount = userIds.stream()
                .filter(userId -> !ACTIVE.equals(memberStates.get(userId)))
                .count();
        if (activeMemberCount + activationCount > MAX_GROUP_MEMBERS) {
            throw error(
                    HttpStatus.CONFLICT,
                    "A group can contain at most " + MAX_GROUP_MEMBERS + " active members."
            );
        }
        lockUsers(sortedDistinct(userIds));
        for (String userId : userIds) {
            requireMutualFollow(conversation.ownerUserId(), userId);
        }
        for (String userId : userIds) {
            activateMember(conversation, userId, memberStates.get(userId));
        }
        return detail(conversationId);
    }

    @Transactional
    public ConversationDetailDto transferOwner(
            String actorUserId,
            String conversationIdValue,
            String targetUserIdValue
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        String targetUserId = requiredId(targetUserIdValue, "userId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        requireOwner(conversation, actorUserId);
        requireActiveConversation(conversation);
        if (actorUserId.equals(targetUserId)) {
            throw error(HttpStatus.CONFLICT, "Ownership is already held by this user.");
        }
        requireActiveMember(conversationId, targetUserId, true);

        int roleUpdates = chatMapper.updateMembershipRolesForTransfer(
                conversationId,
                actorUserId,
                List.of(actorUserId, targetUserId)
        );
        if (roleUpdates != 2) {
            throw new IllegalStateException(
                    "Ownership transfer did not update both active membership roles."
            );
        }
        chatMapper.updateConversationOwner(conversationId, targetUserId);

        ChatConversationRow updated = conversationRow(conversationId, false);
        publishOwnerTransferred(
                updated,
                actorUserId,
                targetUserId
        );
        return detail(conversationId);
    }

    @Transactional
    public ConversationDetailDto dissolve(
            String actorUserId,
            String conversationIdValue
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        requireOwner(conversation, actorUserId);
        if (DISSOLVED.equals(conversation.state())) {
            return detail(conversationId);
        }

        int updatedRows = chatMapper.dissolveConversation(conversationId, actorUserId);
        if (updatedRows != 1) {
            throw new IllegalStateException("Active group dissolution did not update one row.");
        }
        ChatConversationRow dissolved = conversationRow(conversationId, false);
        publishGroupDissolved(dissolved);
        return detail(conversationId);
    }

    @Transactional
    public ConversationDetailDto removeMember(
            String actorUserId,
            String conversationIdValue,
            String targetUserIdValue
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        String targetUserId = requiredId(targetUserIdValue, "userId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        requireOwner(conversation, actorUserId);
        requireActiveConversation(conversation);
        if (conversation.ownerUserId().equals(targetUserId)) {
            throw error(HttpStatus.CONFLICT, "The group owner cannot be removed.");
        }
        requireActiveMember(conversationId, targetUserId, true);
        chatMapper.removeMember(conversationId, targetUserId);
        return detail(conversationId);
    }

    @Transactional
    public boolean leave(String actorUserId, String conversationIdValue) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        requireActiveMember(conversationId, actorUserId, true);
        if (!GROUP.equals(conversation.type())) {
            throw error(HttpStatus.CONFLICT, "Direct conversations cannot be left.");
        }
        requireActiveConversation(conversation);
        if (actorUserId.equals(conversation.ownerUserId())) {
            throw error(
                    HttpStatus.CONFLICT,
                    "The group owner must transfer ownership or dissolve the group before leaving."
            );
        }
        return chatMapper.leaveMember(conversationId, actorUserId) > 0;
    }

    public CursorPageResponse<ChatMessageDto> messages(
            String actorUserId,
            String conversationIdValue,
            String cursorValue,
            int pageSize
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        conversationRow(conversationId, false);
        requireActiveMember(conversationId, actorUserId, false);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        Cursor cursor = CursorCodec.decode(cursorValue);
        Long cursorSequence = cursor == null ? null : cursor.sortValue();
        String cursorId = cursor == null ? null : cursor.id();
        List<ChatMessageRow> rows = chatMapper.findMessages(
                conversationId,
                cursorSequence,
                cursorId,
                safePageSize + 1
        );
        boolean hasMore = rows.size() > safePageSize;
        List<ChatMessageRow> page = hasMore ? rows.subList(0, safePageSize) : rows;
        List<ChatMessageDto> items = page.stream().map(ChatService::messageDto).toList();
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ChatMessageRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(last.sequence(), last.id());
        }
        return new CursorPageResponse<>(items, nextCursor, hasMore);
    }

    @Transactional
    public ChatMessageDto send(
            String actorUserId,
            String conversationIdValue,
            SendMessageRequest request
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        requireActiveMember(conversationId, actorUserId, false);
        requireActiveConversation(conversation);
        if (DIRECT.equals(conversation.type())) {
            lockUsers(List.of(conversation.directUserOneId(), conversation.directUserTwoId()));
            requireMutualFollow(conversation.directUserOneId(), conversation.directUserTwoId());
        }
        NormalizedMessage normalized = normalize(request);
        ChatMessageRow existing = chatMapper.findByClientMessageId(
                conversationId,
                actorUserId,
                normalized.clientMessageId()
        );
        if (existing != null) {
            ChatMessageDto message = messageDto(existing);
            requireSamePayload(message, normalized);
            return message;
        }
        if (VIDEO_SHARE.equals(normalized.type())) {
            requireShareableVideo(normalized.contentId());
        }

        String messageId = newId("msg");
        chatMapper.insertMessage(
                messageId,
                conversationId,
                actorUserId,
                normalized.type(),
                normalized.text(),
                normalized.contentId(),
                normalized.clientMessageId()
        );
        ChatMessageRow created = messageRow(messageId);
        chatMapper.updateConversationLastMessage(
                conversationId,
                messageId,
                created.sequence(),
                created.createdAt()
        );
        ChatMessageDto dto = messageDto(created);
        publishMessageCreated(dto);
        return dto;
    }

    @Transactional
    public ReadReceiptDto markRead(
            String actorUserId,
            String conversationIdValue,
            String messageIdValue
    ) {
        String conversationId = requiredId(conversationIdValue, "conversationId");
        ChatConversationRow conversation = conversationRow(conversationId, true);
        ChatMembershipRow member = requireActiveMember(conversationId, actorUserId, true);
        long targetSequence;
        if (messageIdValue == null || messageIdValue.isBlank()) {
            targetSequence = conversation.lastMessageSequence() == null
                    ? 0
                    : conversation.lastMessageSequence();
        } else {
            String messageId = requiredId(messageIdValue, "messageId");
            Long sequence = chatMapper.findMessageSequence(conversationId, messageId);
            if (sequence == null) {
                throw error(HttpStatus.NOT_FOUND, "Message not found in this conversation.");
            }
            targetSequence = sequence;
        }
        long readSequence = Math.max(member.lastReadSequence(), targetSequence);
        chatMapper.updateReadReceipt(conversationId, actorUserId, readSequence);
        ChatMembershipRow updated = requireActiveMember(
                conversationId,
                actorUserId,
                false
        );
        String lastReadMessageId = null;
        if (updated.lastReadSequence() > 0) {
            lastReadMessageId = chatMapper.findMessageIdBySequence(
                    conversationId,
                    updated.lastReadSequence()
            );
        }
        return new ReadReceiptDto(
                conversationId,
                lastReadMessageId,
                toIso(updated.lastReadAt()),
                unreadCount(conversationId, actorUserId, updated.lastReadSequence())
        );
    }

    private ConversationDetailDto detail(String conversationId) {
        ChatConversationRow conversation = conversationRow(conversationId, false);
        List<ChatMemberDto> members = chatMapper.findActiveMembers(conversationId).stream()
                .map(member -> new ChatMemberDto(
                        member.userId(),
                        member.nickname(),
                        member.avatarUrl(),
                        member.role(),
                        toIso(member.joinedAt())
                ))
                .toList();
        return new ConversationDetailDto(
                conversation.id(),
                conversation.type(),
                conversation.name(),
                conversation.ownerUserId(),
                conversation.state(),
                toIso(conversation.dissolvedAt()),
                conversation.dissolvedByUserId(),
                List.copyOf(members),
                toIso(conversation.createdAt()),
                toIso(conversation.updatedAt())
        );
    }

    private ChatConversationRow conversationRow(String conversationId, boolean forUpdate) {
        ChatConversationRow row = chatMapper.findConversation(conversationId, forUpdate);
        if (row == null) {
            throw error(HttpStatus.NOT_FOUND, "Conversation not found.");
        }
        return row;
    }

    private ChatMembershipRow requireActiveMember(
            String conversationId,
            String userId,
            boolean forUpdate
    ) {
        ChatMembershipRow row = chatMapper.findMembership(
                conversationId,
                userId,
                forUpdate
        );
        if (row == null || !ACTIVE.equals(row.state())) {
            throw error(HttpStatus.FORBIDDEN, "Active conversation membership is required.");
        }
        return row;
    }

    private void requireOwner(ChatConversationRow conversation, String actorUserId) {
        requireActiveMember(conversation.id(), actorUserId, false);
        if (!GROUP.equals(conversation.type())) {
            throw error(HttpStatus.CONFLICT, "This operation is only valid for group conversations.");
        }
        if (!actorUserId.equals(conversation.ownerUserId())) {
            throw error(HttpStatus.FORBIDDEN, "Only the group owner can manage the group.");
        }
    }

    private void requireActiveConversation(ChatConversationRow conversation) {
        if (DISSOLVED.equals(conversation.state())) {
            throw error(HttpStatus.CONFLICT, "Dissolved groups are read-only.");
        }
    }

    private void activateMember(
            ChatConversationRow conversation,
            String userId,
            String currentState
    ) {
        long currentSequence = conversation.lastMessageSequence() == null
                ? 0
                : conversation.lastMessageSequence();
        if (currentState == null) {
            insertMember(conversation.id(), userId, "member", currentSequence);
            return;
        }
        if (ACTIVE.equals(currentState)) {
            return;
        }
        chatMapper.activateMember(conversation.id(), userId, currentSequence);
    }

    private Map<String, String> memberStates(
            String conversationId,
            Collection<String> userIds
    ) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> states = new LinkedHashMap<>();
        List<ChatMemberStateRow> rows = chatMapper.findMemberStates(
                conversationId,
                userIds
        );
        rows.forEach(row -> states.put(row.userId(), row.state()));
        return states;
    }

    private long activeMemberCount(String conversationId) {
        return chatMapper.countActiveMembers(conversationId);
    }

    private void insertMember(
            String conversationId,
            String userId,
            String role,
            long lastReadSequence
    ) {
        chatMapper.insertMember(conversationId, userId, role, lastReadSequence);
    }

    private ChatMessageRow messageRow(String messageId) {
        ChatMessageRow row = chatMapper.findMessageById(messageId);
        if (row == null) {
            throw new IllegalStateException("Inserted chat message was not found: " + messageId);
        }
        return row;
    }

    private void requireShareableVideo(String contentId) {
        if (chatMapper.countShareableVideo(contentId) == 0) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "video_share requires an existing published public video."
            );
        }
    }

    private void requireMutualFollow(String firstUserId, String secondUserId) {
        if (chatMapper.countMutualFollows(firstUserId, secondUserId) != 2) {
            throw error(HttpStatus.FORBIDDEN, "Mutual follow is required.");
        }
    }

    private void lockUsers(Collection<String> userIds) {
        for (String userId : sortedDistinct(userIds)) {
            if (chatMapper.lockUser(userId) == null) {
                throw error(HttpStatus.NOT_FOUND, "User not found: " + userId);
            }
        }
    }

    private long unreadCount(String conversationId, String userId, long readSequence) {
        return chatMapper.countUnreadMessages(conversationId, userId, readSequence);
    }

    private void publishMessageCreated(ChatMessageDto message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventVersion", 1);
        payload.put("conversationId", message.conversationId());
        payload.put("messageId", message.id());
        payload.put("senderUserId", message.senderUserId());
        payload.put("messageType", message.type());
        if (message.sharedContentId() != null) {
            payload.put("sharedContentId", message.sharedContentId());
        }
        payload.put("createdAt", message.createdAt());
        payload.put("message", message);
        eventPublisher.publish(DomainEvent.of(
                EventTypes.CHAT_MESSAGE_CREATED,
                "chat_conversation",
                message.conversationId(),
                payload
        ));
    }

    private void publishOwnerTransferred(
            ChatConversationRow conversation,
            String previousOwnerUserId,
            String newOwnerUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventVersion", 1);
        payload.put("conversationId", conversation.id());
        payload.put("previousOwnerUserId", previousOwnerUserId);
        payload.put("newOwnerUserId", newOwnerUserId);
        payload.put("transferredAt", toIso(conversation.updatedAt()));
        eventPublisher.publish(DomainEvent.of(
                EventTypes.CHAT_GROUP_OWNER_TRANSFERRED,
                "chat_conversation",
                conversation.id(),
                payload
        ));
    }

    private void publishGroupDissolved(ChatConversationRow conversation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventVersion", 1);
        payload.put("conversationId", conversation.id());
        payload.put("dissolvedByUserId", conversation.dissolvedByUserId());
        payload.put("dissolvedAt", toIso(conversation.dissolvedAt()));
        eventPublisher.publish(DomainEvent.of(
                EventTypes.CHAT_GROUP_DISSOLVED,
                "chat_conversation",
                conversation.id(),
                payload
        ));
    }

    private static NormalizedMessage normalize(SendMessageRequest request) {
        if (request == null) {
            throw error(HttpStatus.BAD_REQUEST, "Message request is required.");
        }
        if (request.type() != null && request.type().length() > MAX_MESSAGE_TYPE_LENGTH) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "type must be at most " + MAX_MESSAGE_TYPE_LENGTH + " characters."
            );
        }
        String type = request.type() == null ? "" : request.type().trim().toLowerCase(Locale.ROOT);
        String clientMessageId = request.clientMessageId() == null
                ? ""
                : request.clientMessageId().trim();
        if (clientMessageId.isEmpty() || clientMessageId.length() > 128) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "clientMessageId must be non-blank and at most 128 characters."
            );
        }
        if (TEXT.equals(type)) {
            if (request.text() == null || request.text().isBlank()) {
                throw error(HttpStatus.BAD_REQUEST, "text messages require non-blank text.");
            }
            if (request.text().length() > MAX_TEXT_LENGTH) {
                throw error(
                        HttpStatus.BAD_REQUEST,
                        "text must be at most " + MAX_TEXT_LENGTH + " characters."
                );
            }
            if (request.contentId() != null && !request.contentId().isBlank()) {
                throw error(HttpStatus.BAD_REQUEST, "text messages cannot reference content.");
            }
            return new NormalizedMessage(type, request.text(), null, clientMessageId);
        }
        if (VIDEO_SHARE.equals(type)) {
            String contentId = request.contentId() == null ? "" : request.contentId().trim();
            if (contentId.isEmpty() || contentId.length() > 64) {
                throw error(HttpStatus.BAD_REQUEST, "video_share messages require contentId.");
            }
            if (request.text() != null && !request.text().isBlank()) {
                throw error(HttpStatus.BAD_REQUEST, "video_share messages cannot include text.");
            }
            return new NormalizedMessage(type, null, contentId, clientMessageId);
        }
        throw error(HttpStatus.BAD_REQUEST, "type must be text or video_share.");
    }

    private static void requireSamePayload(
            ChatMessageDto existing,
            NormalizedMessage requested
    ) {
        if (!existing.type().equals(requested.type())
                || !java.util.Objects.equals(existing.text(), requested.text())
                || !java.util.Objects.equals(existing.sharedContentId(), requested.contentId())) {
            throw error(
                    HttpStatus.CONFLICT,
                    "clientMessageId was already used with a different message payload."
            );
        }
    }

    private static ConversationSummaryDto conversationSummaryDto(
            ChatConversationSummaryRow row,
            List<AvatarMemberDto> avatarMembers
    ) {
        ChatMessageDto lastMessage = row.messageId() == null
                ? null
                : new ChatMessageDto(
                        row.messageId(),
                        row.messageConversationId(),
                        row.messageSenderUserId(),
                        row.messageSenderNickname(),
                        row.messageSenderAvatarUrl(),
                        row.messageType(),
                        row.messageBody(),
                        row.messageSharedContentId(),
                        sharedContentDto(
                                row.messageType(),
                                row.messageSharedContentId(),
                                row.messageSharedContentTitle(),
                                row.messageSharedContentCoverUrl(),
                                row.messageSharedContentAuthorUserId(),
                                row.messageSharedContentAuthorNickname()
                        ),
                        row.messageClientMessageId(),
                        toIso(row.messageCreatedAt())
                );
        return new ConversationSummaryDto(
                row.id(),
                row.type(),
                row.name(),
                row.displayName(),
                row.displayAvatarUrl(),
                avatarMembers,
                row.ownerUserId(),
                row.state(),
                toIso(row.dissolvedAt()),
                lastMessage,
                row.unreadCount(),
                // Expose the same value the list is ordered by (activity time),
                // so the DTO never disagrees with the cursor's sort key.
                toIso(row.activityAt())
        );
    }

    private static ChatMessageDto messageDto(ChatMessageRow row) {
        return new ChatMessageDto(
                row.id(),
                row.conversationId(),
                row.senderUserId(),
                row.senderNickname(),
                row.senderAvatarUrl(),
                row.messageType(),
                row.body(),
                row.sharedContentId(),
                sharedContentDto(
                        row.messageType(),
                        row.sharedContentId(),
                        row.sharedContentTitle(),
                        row.sharedContentCoverUrl(),
                        row.sharedContentAuthorUserId(),
                        row.sharedContentAuthorNickname()
                ),
                row.clientMessageId(),
                toIso(row.createdAt())
        );
    }

    private static SharedContentDto sharedContentDto(
            String messageType,
            String sharedContentId,
            String title,
            String coverUrl,
            String authorUserId,
            String authorNickname
    ) {
        return VIDEO_SHARE.equals(messageType) && sharedContentId != null
                ? new SharedContentDto(
                        sharedContentId,
                        title,
                        coverUrl,
                        authorUserId,
                        authorNickname
                )
                : null;
    }

    private static LinkedHashSet<String> normalizedIds(List<String> values, String field) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(requiredId(value, field));
        }
        return result;
    }

    private static void requireListSize(List<String> values, int maximumSize, String field) {
        if (values != null && values.size() > maximumSize) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    field + " must contain at most " + maximumSize + " entries."
            );
        }
    }

    private static List<String> sortedDistinct(Collection<String> values) {
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String requiredId(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    field + " must be non-blank and at most 64 characters."
            );
        }
        return normalized;
    }

    private static String requiredName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 120) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "name must be non-blank and at most 120 characters."
            );
        }
        return name;
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String toIso(Instant timestamp) {
        return timestamp == null ? null : timestamp.toString();
    }

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L
        );
    }

    private static Instant fromEpochMicros(long epochMicros) {
        long seconds = Math.floorDiv(epochMicros, 1_000_000L);
        long micros = Math.floorMod(epochMicros, 1_000_000L);
        return Instant.ofEpochSecond(seconds, micros * 1_000L);
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private record NormalizedMessage(
            String type,
            String text,
            String contentId,
            String clientMessageId
    ) {
    }
}
