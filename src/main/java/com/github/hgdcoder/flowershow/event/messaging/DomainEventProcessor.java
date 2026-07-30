package com.github.hgdcoder.flowershow.event.messaging;

import com.github.hgdcoder.flowershow.chat.realtime.ChatRealtimeEventBroadcaster;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.persistence.mapper.event.DomainEventMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DomainEventProcessor {

    private final DomainEventMapper domainEventMapper;
    private final NotificationWriter notificationWriter;
    private final ContentFanoutDispatcher contentFanoutDispatcher;
    private final ChatRealtimeEventBroadcaster chatRealtimeEventBroadcaster;

    public DomainEventProcessor(
            DomainEventMapper domainEventMapper,
            NotificationWriter notificationWriter,
            ContentFanoutDispatcher contentFanoutDispatcher,
            ChatRealtimeEventBroadcaster chatRealtimeEventBroadcaster
    ) {
        this.domainEventMapper = domainEventMapper;
        this.notificationWriter = notificationWriter;
        this.contentFanoutDispatcher = contentFanoutDispatcher;
        this.chatRealtimeEventBroadcaster = chatRealtimeEventBroadcaster;
    }

    public void process(EventEnvelope event) {
        switch (event.eventType()) {
            case EventTypes.COMMENT_CREATED -> notifyForCommentCreated(event);
            case EventTypes.COMMENT_LIKED -> notifyCommentOwner(event);
            case EventTypes.CONTENT_LIKED -> notifyContentOwner(event, "like", "Someone liked your post.");
            case EventTypes.CONTENT_FAVORITED -> notifyContentOwner(event, "favorite", "Someone saved your post.");
            case EventTypes.CONTENT_PUBLISHED -> contentFanoutDispatcher.dispatch(event);
            case EventTypes.USER_FOLLOWED -> notifyFollowedUser(event);
            case EventTypes.CHAT_MESSAGE_CREATED -> broadcastChatMessage(event);
            default -> {
            }
        }
    }

    private void broadcastChatMessage(EventEnvelope event) {
        if (!chatRealtimeEventBroadcaster.hasConnections()) {
            return;
        }
        String conversationId = asString(event.payload().get("conversationId"));
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        List<String> activeUserIds =
                domainEventMapper.findActiveConversationMemberUserIds(conversationId);
        if (!activeUserIds.isEmpty()) {
            chatRealtimeEventBroadcaster.broadcastMessageCreated(event, activeUserIds);
        }
    }

    private void notifyForCommentCreated(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        String actorUserId = asString(payload.get("actorUserId"));
        String contentId = asString(payload.get("contentId"));
        String commentId = asString(payload.get("commentId"));
        String parentId = asString(payload.get("parentId"));

        String receiverUserId;
        if (parentId != null && !parentId.isBlank()) {
            receiverUserId = domainEventMapper.findCommentAuthorUserId(parentId);
        } else {
            receiverUserId = findContentAuthorUserId(contentId);
        }
        notificationWriter.insert(
                event.eventId(), receiverUserId, actorUserId, "comment", contentId, commentId,
                "Someone commented on your post.", payload
        );
    }

    private void notifyCommentOwner(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        String actorUserId = asString(payload.get("actorUserId"));
        String contentId = asString(payload.get("contentId"));
        String commentId = asString(payload.get("commentId"));
        String receiverUserId = findCommentAuthorUserId(commentId);
        notificationWriter.insert(
                event.eventId(), receiverUserId, actorUserId, "comment_like", contentId, commentId,
                "Someone liked your comment.", payload
        );
    }

    private void notifyContentOwner(EventEnvelope event, String type, String message) {
        Map<String, Object> payload = event.payload();
        String actorUserId = asString(payload.get("actorUserId"));
        String contentId = asString(payload.get("contentId"));
        String receiverUserId = findContentAuthorUserId(contentId);
        notificationWriter.insert(
                event.eventId(), receiverUserId, actorUserId, type, contentId,
                asString(payload.get("commentId")), message, payload
        );
    }

    private void notifyFollowedUser(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        String actorUserId = asString(payload.get("followerId"));
        String receiverUserId = asString(payload.get("followingId"));
        notificationWriter.insert(
                event.eventId(), receiverUserId, actorUserId, "follow", null, null,
                "Someone followed you.", payload
        );
    }

    private String findCommentAuthorUserId(String commentId) {
        if (commentId == null || commentId.isBlank()) {
            return null;
        }
        return domainEventMapper.findCommentAuthorUserId(commentId);
    }

    private String findContentAuthorUserId(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        return domainEventMapper.findContentAuthorUserId(contentId);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
