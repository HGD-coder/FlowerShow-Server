package com.github.hgdcoder.flowershow.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.chat.ChatModels.ChatMessageDto;
import com.github.hgdcoder.flowershow.event.messaging.EventEnvelope;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ChatRealtimeEventBroadcaster {

    private final ChatChannelRegistry channelRegistry;
    private final ObjectMapper objectMapper;

    public ChatRealtimeEventBroadcaster(
            ChatChannelRegistry channelRegistry,
            ObjectMapper objectMapper
    ) {
        this.channelRegistry = channelRegistry;
        this.objectMapper = objectMapper;
    }

    public boolean hasConnections() {
        return channelRegistry.hasConnections();
    }

    public void broadcastMessageCreated(EventEnvelope event, Collection<String> activeUserIds) {
        Object message = event.payload().get("message");
        if (!(message instanceof Map<?, ?>)) {
            return;
        }
        ChatMessageDto chatMessage = objectMapper.convertValue(message, ChatMessageDto.class);
        String json;
        try {
            json = objectMapper.writeValueAsString(new ChatMessageCreatedFrame(
                    "chat.message.created",
                    event.eventId(),
                    event.occurredAt(),
                    chatMessage
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize chat WebSocket event.", e);
        }
        channelRegistry.broadcast(activeUserIds, json);
    }

    private record ChatMessageCreatedFrame(
            String type,
            String eventId,
            Instant occurredAt,
            ChatMessageDto message
    ) {
    }
}
