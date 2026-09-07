package com.github.hgdcoder.flowershow.maintenance;

import com.github.hgdcoder.flowershow.persistence.mapper.event.OutboxEventMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.event.ProcessedEventMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.event.PushDeliveryMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.RecommendationMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled retention cleanup for append-only bookkeeping tables that would
 * otherwise grow without bound: processed/dead outbox events, dedup markers,
 * terminal push deliveries, expired recommendation sessions/requests and
 * consumed recommendation client events.
 */
@Component
@ConditionalOnProperty(
        name = "flower-show.maintenance.purge-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MaintenancePurgeJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenancePurgeJob.class);

    private final OutboxEventMapper outboxEventMapper;
    private final ProcessedEventMapper processedEventMapper;
    private final PushDeliveryMapper pushDeliveryMapper;
    private final RecommendationMapper recommendationMapper;
    private final Duration retention;
    private final Duration expiredRowGrace;

    public MaintenancePurgeJob(
            OutboxEventMapper outboxEventMapper,
            ProcessedEventMapper processedEventMapper,
            PushDeliveryMapper pushDeliveryMapper,
            RecommendationMapper recommendationMapper,
            @Value("${flower-show.maintenance.retention-days:30}") long retentionDays
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.processedEventMapper = processedEventMapper;
        this.pushDeliveryMapper = pushDeliveryMapper;
        this.recommendationMapper = recommendationMapper;
        this.retention = Duration.ofDays(Math.max(1, retentionDays));
        this.expiredRowGrace = Duration.ofDays(1);
    }

    @Scheduled(cron = "${flower-show.maintenance.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purge() {
        Instant now = Instant.now();
        Timestamp retentionCutoff = Timestamp.from(now.minus(retention));
        Instant recommendationCutoff = now.minus(expiredRowGrace);

        int outboxProcessed = outboxEventMapper.deleteProcessedOlderThan(retentionCutoff);
        int outboxDead = outboxEventMapper.deleteDeadOlderThan(retentionCutoff);
        int processed = processedEventMapper.deleteOlderThan(retentionCutoff);
        int deliveries = pushDeliveryMapper.deleteTerminalDeliveriesOlderThan(retentionCutoff);
        int sessions = recommendationMapper.deleteExpiredSessions(recommendationCutoff);
        int requests = recommendationMapper.deleteExpiredClientRequests(recommendationCutoff);
        int clientEvents = recommendationMapper.deleteExpiredClientEvents(now.minus(retention));

        int total = outboxProcessed + outboxDead + processed + deliveries
                + sessions + requests + clientEvents;
        if (total > 0) {
            LOGGER.info(
                    "Maintenance purge removed rows: outboxProcessed={}, outboxDead={}, "
                            + "processedEvents={}, terminalDeliveries={}, sessions={}, requests={}, clientEvents={}",
                    outboxProcessed,
                    outboxDead,
                    processed,
                    deliveries,
                    sessions,
                    requests,
                    clientEvents
            );
        }
    }
}
