package com.github.hgdcoder.flowershow.event.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flower-show.messaging.kafka.enabled", havingValue = "true")
public class KafkaContentFanoutDispatcher implements ContentFanoutDispatcher {

    private final FanoutPlanner fanoutPlanner;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String fanoutTopic;

    public KafkaContentFanoutDispatcher(
            FanoutPlanner fanoutPlanner,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${flower-show.messaging.kafka.fanout-topic:flower-show.feed-fanout.v1}") String fanoutTopic
    ) {
        this.fanoutPlanner = fanoutPlanner;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.fanoutTopic = fanoutTopic;
    }

    @Override
    public void dispatch(EventEnvelope event) {
        fanoutPlanner.plan(event, this::publishChunk);
    }

    private void publishChunk(FanoutChunkMessage chunk) {
        try {
            String value = objectMapper.writeValueAsString(chunk);
            kafkaTemplate.send(fanoutTopic, chunk.chunkId(), value).get(30, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize fanout chunk " + chunk.chunkId() + ".", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish fanout chunk " + chunk.chunkId() + ".", e);
        }
    }
}
