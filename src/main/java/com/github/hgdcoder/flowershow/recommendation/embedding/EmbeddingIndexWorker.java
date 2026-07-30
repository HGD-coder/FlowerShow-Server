package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.github.hgdcoder.flowershow.config.EmbeddingProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "flower-show.recommendation.embedding.enabled",
        havingValue = "true"
)
public class EmbeddingIndexWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingIndexWorker.class);

    private final EmbeddingIndexService indexService;
    private final EmbeddingProperties properties;
    private final ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> scheduledTask;

    public EmbeddingIndexWorker(
            EmbeddingIndexService indexService,
            EmbeddingProperties properties,
            @Qualifier("embeddingTaskScheduler") ThreadPoolTaskScheduler scheduler
    ) {
        this.indexService = indexService;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void schedule() {
        scheduledTask = scheduler.scheduleWithFixedDelay(
                this::runSafely,
                Instant.now().plus(properties.initialDelay()),
                properties.refreshInterval()
        );
    }

    @PreDestroy
    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
    }

    void runSafely() {
        try {
            EmbeddingIndexService.IndexResult result = indexService.indexOnce();
            if (result.total() > 0) {
                LOGGER.info(
                        "Embedding index updated: contents={}, suggestions={}, profiles={}, removed={}.",
                        result.contents(),
                        result.suggestions(),
                        result.userProfiles(),
                        result.removed()
                );
            }
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Embedding index cycle failed ({}); lexical recommendation remains active.",
                    error.getClass().getSimpleName()
            );
        }
    }
}
