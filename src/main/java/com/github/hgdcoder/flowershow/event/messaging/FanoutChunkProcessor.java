package com.github.hgdcoder.flowershow.event.messaging;

import com.github.hgdcoder.flowershow.persistence.mapper.event.FanoutMapper;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FanoutChunkProcessor {

    private static final String CONSUMER_NAME = "feed-fanout-worker-v1";

    private final FanoutMapper fanoutMapper;
    private final NotificationWriter notificationWriter;
    private final ProcessedEventStore processedEventStore;

    public FanoutChunkProcessor(
            FanoutMapper fanoutMapper,
            NotificationWriter notificationWriter,
            ProcessedEventStore processedEventStore
    ) {
        this.fanoutMapper = fanoutMapper;
        this.notificationWriter = notificationWriter;
        this.processedEventStore = processedEventStore;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(FanoutChunkMessage chunk) {
        if (processedEventStore.isProcessed(CONSUMER_NAME, chunk.chunkId())) {
            return;
        }
        for (FanoutRecipient recipient : chunk.recipients()) {
            if (recipient.addToFeed()) {
                insertFeedEntry(chunk, recipient.userId());
            }
            if (recipient.notifyNewContent()) {
                notificationWriter.insert(
                        chunk.sourceEventId(),
                        recipient.userId(),
                        chunk.authorUserId(),
                        "new_post",
                        chunk.contentId(),
                        null,
                        "An author you follow published a new post.",
                        Map.of(
                                "contentId", chunk.contentId(),
                                "authorUserId", chunk.authorUserId(),
                                "title", chunk.contentTitle()
                        )
                );
            }
        }
        processedEventStore.markProcessed(CONSUMER_NAME, chunk.chunkId());
    }

    private void insertFeedEntry(FanoutChunkMessage chunk, String userId) {
        fanoutMapper.insertFeedEntry(
                userId,
                chunk.contentId(),
                chunk.authorUserId(),
                chunk.sourceEventId(),
                chunk.score()
        );
    }
}
