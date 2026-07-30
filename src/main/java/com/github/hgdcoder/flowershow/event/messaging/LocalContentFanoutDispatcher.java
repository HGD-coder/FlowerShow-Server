package com.github.hgdcoder.flowershow.event.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "flower-show.messaging.kafka.enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class LocalContentFanoutDispatcher implements ContentFanoutDispatcher {

    private final FanoutPlanner fanoutPlanner;
    private final FanoutChunkProcessor chunkProcessor;

    public LocalContentFanoutDispatcher(FanoutPlanner fanoutPlanner, FanoutChunkProcessor chunkProcessor) {
        this.fanoutPlanner = fanoutPlanner;
        this.chunkProcessor = chunkProcessor;
    }

    @Override
    public void dispatch(EventEnvelope event) {
        fanoutPlanner.plan(event, chunkProcessor::process);
    }
}
