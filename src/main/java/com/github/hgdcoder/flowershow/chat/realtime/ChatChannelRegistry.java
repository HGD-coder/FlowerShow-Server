package com.github.hgdcoder.flowershow.chat.realtime;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class ChatChannelRegistry {

    private final ConcurrentMap<String, Set<Channel>> channelsByUser = new ConcurrentHashMap<>();

    public void register(String userId, Channel channel) {
        channelsByUser.compute(userId, (key, existing) -> {
            Set<Channel> channels = existing == null
                    ? ConcurrentHashMap.newKeySet()
                    : existing;
            channels.add(channel);
            return channels;
        });
        channel.closeFuture().addListener(ignored -> remove(userId, channel));
    }

    public boolean hasConnections() {
        return !channelsByUser.isEmpty();
    }

    public void broadcast(Collection<String> userIds, String json) {
        for (String userId : userIds) {
            Set<Channel> channels = channelsByUser.get(userId);
            if (channels == null) {
                continue;
            }
            for (Channel channel : channels) {
                // Skip channels whose outbound buffer is already saturated:
                // realtime frames are best-effort by design and a slow consumer
                // must catch up through the REST history API, so dropping beats
                // letting the Netty outbound buffer grow without bound.
                if (channel.isActive() && channel.isWritable()) {
                    channel.writeAndFlush(new TextWebSocketFrame(json));
                }
            }
        }
    }

    private void remove(String userId, Channel channel) {
        channelsByUser.computeIfPresent(userId, (key, channels) -> {
            channels.remove(channel);
            return channels.isEmpty() ? null : channels;
        });
    }
}
