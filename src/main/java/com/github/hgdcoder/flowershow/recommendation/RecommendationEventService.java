package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.event.DomainEvent;
import com.github.hgdcoder.flowershow.event.EventPublisher;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Actor;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ClientEvent;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.EventBatchData;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.EventResult;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ServeSession;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.SnapshotItem;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository.StoredEvent;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.ExposureClaims;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.TokenValidationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationEventService {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "impression",
            "play_start",
            "suggestion_click",
            "search_submit",
            "watch",
            "like",
            "favorite",
            "share"
    );
    private static final long MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L;
    private static final long MAX_EVENT_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long MAX_WATCH_MS = 24L * 60 * 60 * 1000;

    private final RecommendationRepository repository;
    private final RecommendationTokenService tokens;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public RecommendationEventService(
            RecommendationRepository repository,
            RecommendationTokenService tokens,
            EventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.tokens = tokens;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EventBatchData process(Actor actor, List<ClientEvent> events) {
        if (events == null) {
            return new EventBatchData(List.of());
        }
        return new EventBatchData(events.stream()
                .map(event -> event == null
                        ? EventResult.rejected("", "INVALID_EVENT", "Event entries must not be null.")
                        : processOne(actor, event))
                .toList());
    }

    private EventResult processOne(Actor actor, ClientEvent event) {
        String eventId = trim(event.eventId());
        if (!SAFE_ID.matcher(eventId).matches()) {
            return reject(eventId, "INVALID_EVENT_ID", "eventId must be 1-128 safe characters.");
        }
        String type = trim(event.type()).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            return reject(eventId, "UNSUPPORTED_EVENT_TYPE", "Unsupported event type.");
        }
        long now = System.currentTimeMillis();
        if (event.occurredAtMs() == null
                || event.occurredAtMs() <= 0
                || event.occurredAtMs() > now + MAX_FUTURE_SKEW_MS
                || event.occurredAtMs() < now - MAX_EVENT_AGE_MS) {
            return reject(eventId, "INVALID_OCCURRED_AT", "occurredAtMs is outside the accepted window.");
        }
        if (event.query() != null && event.query().length() > 200) {
            return reject(eventId, "QUERY_TOO_LONG", "query exceeds 200 characters.");
        }
        if ("search_submit".equals(type) && RecommendationService.normalize(event.query()).isBlank()) {
            return reject(eventId, "QUERY_REQUIRED", "search_submit requires query.");
        }
        if ("watch".equals(type)
                && (event.watchMs() == null || event.watchMs() < 0 || event.watchMs() > MAX_WATCH_MS)) {
            return reject(eventId, "INVALID_WATCH_MS", "watch requires watchMs within the accepted range.");
        }
        if (event.playbackId() != null && event.playbackId().length() > 128) {
            return reject(eventId, "INVALID_PLAYBACK_ID", "playbackId is too long.");
        }
        if (event.sequence() != null && event.sequence() < 0) {
            return reject(eventId, "INVALID_SEQUENCE", "sequence must be non-negative.");
        }

        String fingerprint = RecommendationActorResolver.sha256Hex(actor.actorKey() + "|" + writeJson(event));
        Optional<StoredEvent> existing = repository.findEvent(actor.actorKey(), eventId);
        if (existing.isPresent()) {
            return existing.get().fingerprint().equals(fingerprint)
                    ? EventResult.duplicate(eventId)
                    : reject(eventId, "EVENT_ID_REUSE", "eventId was already used for another event.");
        }

        ValidatedExposure validated = null;
        if (!"search_submit".equals(type) || !trim(event.exposureToken()).isBlank()) {
            validated = validateExposure(actor, event, type, eventId);
            if (validated.rejection() != null) {
                return validated.rejection();
            }
        }

        String entityId = validated == null ? null : validated.claims().entityId();
        String sessionId = validated == null ? null : validated.claims().serveSessionId();
        int inserted = repository.insertEvent(
                actor.actorKey(),
                eventId,
                fingerprint,
                type,
                entityId,
                sessionId,
                event.occurredAtMs()
        );
        if (inserted == 0) {
            Optional<StoredEvent> raced = repository.findEvent(actor.actorKey(), eventId);
            return raced.isPresent() && raced.get().fingerprint().equals(fingerprint)
                    ? EventResult.duplicate(eventId)
                    : reject(eventId, "EVENT_ID_REUSE", "eventId was already used for another event.");
        }

        updateInterests(actor.actorKey(), type, event, validated);
        eventPublisher.publish(DomainEvent.of(
                EventTypes.RECOMMENDATION_BEHAVIOR,
                "recommendation_actor",
                eventId,
                Map.of(
                        "eventId", eventId,
                        "actorKey", actor.actorKey(),
                        "behaviorType", type,
                        "kind", validated == null ? "query" : validated.claims().kind(),
                        "entityId", entityId == null ? "" : entityId,
                        "occurredAtMs", event.occurredAtMs()
                )
        ));
        return EventResult.accepted(eventId);
    }

    private ValidatedExposure validateExposure(Actor actor, ClientEvent event, String type, String eventId) {
        String rawToken = trim(event.exposureToken());
        if (rawToken.isBlank()) {
            return rejectedExposure(eventId, "EXPOSURE_TOKEN_REQUIRED",
                    "This event type requires exposureToken.");
        }
        if (rawToken.length() > 4096) {
            return rejectedExposure(eventId, "INVALID_EXPOSURE_TOKEN",
                    "Exposure token verification failed.");
        }
        ExposureClaims claims;
        try {
            claims = tokens.verifyExposure(rawToken);
        } catch (TokenValidationException e) {
            return rejectedExposure(eventId, "INVALID_EXPOSURE_TOKEN",
                    "Exposure token verification failed.");
        }
        if (!claims.actorKey().equals(actor.actorKey())) {
            return rejectedExposure(eventId, "EXPOSURE_ACTOR_MISMATCH",
                    "Exposure token belongs to another actor.");
        }
        if (claims.expiresAtMs() <= System.currentTimeMillis()) {
            return rejectedExposure(eventId, "EXPOSURE_TOKEN_EXPIRED", "Exposure token has expired.");
        }
        String suppliedEntity = RecommendationService.GUESS_KIND.equals(claims.kind())
                ? trim(event.suggestionId())
                : trim(event.contentId());
        if (!suppliedEntity.isBlank() && !suppliedEntity.equals(claims.entityId())) {
            return rejectedExposure(eventId, "EXPOSURE_ENTITY_MISMATCH",
                    "Event entity does not match exposure token.");
        }
        if ("suggestion_click".equals(type) && !RecommendationService.GUESS_KIND.equals(claims.kind())) {
            return rejectedExposure(eventId, "EXPOSURE_KIND_MISMATCH",
                    "Suggestion event requires a suggestion exposure.");
        }
        if (!"suggestion_click".equals(type)
                && !"search_submit".equals(type)
                && RecommendationService.GUESS_KIND.equals(claims.kind())) {
            return rejectedExposure(eventId, "EXPOSURE_KIND_MISMATCH",
                    "Content event requires a content exposure.");
        }
        Optional<ServeSession> sessionValue = repository.findSession(claims.serveSessionId());
        if (sessionValue.isEmpty()) {
            return rejectedExposure(eventId, "EXPOSURE_SESSION_NOT_FOUND",
                    "Exposure session was not found.");
        }
        ServeSession session = sessionValue.get();
        if (!session.actorKey().equals(actor.actorKey())
                || !session.kind().equals(claims.kind())
                || claims.rank() < 1
                || claims.rank() > session.snapshot().items().size()
                || !session.snapshot().items().get(claims.rank() - 1).entityId().equals(claims.entityId())) {
            return rejectedExposure(eventId, "INVALID_EXPOSURE_TOKEN",
                    "Exposure token does not match the immutable session snapshot.");
        }
        return new ValidatedExposure(claims, session.snapshot().items().get(claims.rank() - 1), null);
    }

    private void updateInterests(
            String actorKey,
            String type,
            ClientEvent event,
            ValidatedExposure exposure
    ) {
        if ("search_submit".equals(type)) {
            repository.addInterest(actorKey, RecommendationService.normalize(event.query()), 20.0);
            return;
        }
        if (exposure == null) {
            return;
        }
        if ("suggestion_click".equals(type)) {
            repository.addInterest(actorKey, exposure.snapshotItem().text(), 20.0);
            return;
        }
        double delta = switch (type) {
            case "impression" -> 0.05;
            case "play_start" -> 0.4;
            case "watch" -> Math.max(0.1, Math.min(3.0, event.watchMs() / 30_000.0));
            case "like" -> 3.0;
            case "favorite" -> 4.0;
            case "share" -> 3.5;
            default -> 0.0;
        };
        repository.contentTerms(exposure.claims().entityId())
                .forEach(term -> repository.addInterest(actorKey, term, delta));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint recommendation event.", e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static EventResult reject(String eventId, String code, String message) {
        return EventResult.rejected(eventId, code, message);
    }

    private static ValidatedExposure rejectedExposure(String eventId, String code, String message) {
        return new ValidatedExposure(null, null, reject(eventId, code, message));
    }

    private record ValidatedExposure(
            ExposureClaims claims,
            SnapshotItem snapshotItem,
            EventResult rejection
    ) {
    }
}
