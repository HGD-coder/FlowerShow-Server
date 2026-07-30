package com.github.hgdcoder.flowershow.event.messaging;

import com.github.hgdcoder.flowershow.persistence.mapper.event.FanoutMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.event.FollowerPreferenceRow;
import com.github.hgdcoder.flowershow.persistence.mapper.event.PublishedContentRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FanoutPlanner {

    private final FanoutMapper fanoutMapper;
    private final long fanoutOnWriteMaxFollowers;
    private final int chunkSize;

    public FanoutPlanner(
            FanoutMapper fanoutMapper,
            @Value("${flower-show.feed.fanout-on-write-max-followers:100000}") long fanoutOnWriteMaxFollowers,
            @Value("${flower-show.feed.fanout-chunk-size:1000}") int chunkSize
    ) {
        this.fanoutMapper = fanoutMapper;
        this.fanoutOnWriteMaxFollowers = Math.max(0, fanoutOnWriteMaxFollowers);
        this.chunkSize = Math.min(5000, Math.max(100, chunkSize));
    }

    public void plan(EventEnvelope event, Consumer<FanoutChunkMessage> chunkConsumer) {
        String contentId = asString(event.payload().get("contentId"));
        if (contentId == null || contentId.isBlank()) {
            contentId = event.aggregateId();
        }
        PublishedContentRow content = fanoutMapper.findPublishedContent(contentId);
        if (content == null) {
            throw new IllegalStateException("Published content no longer exists: " + contentId);
        }

        if (!"published".equals(content.status()) || !"public".equals(content.visibility())) {
            return;
        }

        String fanoutMode = content.fanoutMode().toLowerCase(Locale.ROOT);
        boolean addToFeed = "push".equals(fanoutMode)
                || ("auto".equals(fanoutMode) && content.followerCount() <= fanoutOnWriteMaxFollowers);
        String cursor = "";
        int sequence = 0;

        while (true) {
            List<FollowerPreferenceRow> followers =
                    followerPage(content.authorUserId(), cursor, addToFeed);
            if (followers.isEmpty()) {
                return;
            }
            List<FanoutRecipient> recipients = followers.stream()
                    .map(follower -> new FanoutRecipient(
                            follower.userId(),
                            addToFeed,
                            follower.notifyNewContent()
                    ))
                    .toList();
            chunkConsumer.accept(new FanoutChunkMessage(
                    chunkId(event.eventId(), sequence, recipients),
                    event.eventId(),
                    content.id(),
                    content.authorUserId(),
                    content.title(),
                    content.publishTime(),
                    recipients
            ));
            sequence++;
            cursor = followers.get(followers.size() - 1).userId();
            if (followers.size() < chunkSize) {
                return;
            }
        }
    }

    private List<FollowerPreferenceRow> followerPage(
            String authorUserId,
            String cursor,
            boolean addToFeed
    ) {
        return fanoutMapper.findFollowerPage(authorUserId, cursor, !addToFeed, chunkSize);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String chunkId(String eventId, int sequence, List<FanoutRecipient> recipients) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (FanoutRecipient recipient : recipients) {
                digest.update(recipient.userId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) (recipient.addToFeed() ? 1 : 0));
                digest.update((byte) (recipient.notifyNewContent() ? 1 : 0));
            }
            String fingerprint = HexFormat.of().formatHex(digest.digest(), 0, 8);
            return eventId + "-" + sequence + "-" + fingerprint;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

}
