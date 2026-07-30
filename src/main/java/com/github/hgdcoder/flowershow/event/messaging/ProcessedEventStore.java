package com.github.hgdcoder.flowershow.event.messaging;

import com.github.hgdcoder.flowershow.persistence.mapper.event.ProcessedEventMapper;
import org.springframework.stereotype.Component;

@Component
public class ProcessedEventStore {

    private final ProcessedEventMapper processedEventMapper;

    public ProcessedEventStore(ProcessedEventMapper processedEventMapper) {
        this.processedEventMapper = processedEventMapper;
    }

    public boolean isProcessed(String consumerName, String eventId) {
        return processedEventMapper.countProcessed(consumerName, eventId) > 0;
    }

    public void markProcessed(String consumerName, String eventId) {
        processedEventMapper.insertProcessed(consumerName, eventId);
    }
}
