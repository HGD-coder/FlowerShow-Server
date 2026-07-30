package com.github.hgdcoder.flowershow.recommendation;

import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Actor;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ApiEnvelope;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.EventBatchRequest;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.FeedPageRequest;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.GuessPageRequest;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.SearchVideosRequest;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.StoredHttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class RecommendationController {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_BATCH_SIZE = 100;
    private static final long MAX_REQUEST_BYTES = 64 * 1024;

    private final RecommendationActorResolver actorResolver;
    private final RecommendationService recommendationService;
    private final RecommendationEventService eventService;

    public RecommendationController(
            RecommendationActorResolver actorResolver,
            RecommendationService recommendationService,
            RecommendationEventService eventService
    ) {
        this.actorResolver = actorResolver;
        this.recommendationService = recommendationService;
        this.eventService = eventService;
    }

    @PostMapping(path = "/feed/pages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> feed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Install-Id", required = false) String installId,
            HttpServletRequest servletRequest,
            @RequestBody FeedPageRequest request
    ) {
        validateRequestSize(servletRequest);
        Actor actor = actorResolver.resolve(jwt, installId);
        String clientRequestId = clientRequestId(request.clientRequestId());
        int limit = limit(request.limit());
        optional(request.serveSessionId(), "SERVE_SESSION_ID", 128);
        optional(request.cursor(), "CURSOR", 4096);
        optional(request.scene(), "SCENE", 64);
        StoredHttpResponse response = recommendationService.respondIdempotently(
                actor,
                "feed",
                clientRequestId,
                request,
                () -> recommendationService.feed(
                        actor,
                        request.serveSessionId(),
                        request.cursor(),
                        limit,
                        request.scene(),
                        Boolean.TRUE.equals(request.refresh())
                )
        );
        return stored(response);
    }

    @PostMapping(path = "/search/guesses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> guesses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Install-Id", required = false) String installId,
            HttpServletRequest servletRequest,
            @RequestBody GuessPageRequest request
    ) {
        validateRequestSize(servletRequest);
        Actor actor = actorResolver.resolve(jwt, installId);
        String clientRequestId = clientRequestId(request.clientRequestId());
        int limit = limit(request.limit());
        optional(request.serveSessionId(), "SERVE_SESSION_ID", 128);
        optional(request.cursor(), "CURSOR", 4096);
        StoredHttpResponse response = recommendationService.respondIdempotently(
                actor,
                "guesses",
                clientRequestId,
                request,
                () -> recommendationService.guesses(
                        actor,
                        request.serveSessionId(),
                        request.cursor(),
                        limit,
                        Boolean.TRUE.equals(request.refresh())
                )
        );
        return stored(response);
    }

    @PostMapping(path = "/search/videos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Install-Id", required = false) String installId,
            HttpServletRequest servletRequest,
            @RequestBody SearchVideosRequest request
    ) {
        validateRequestSize(servletRequest);
        Actor actor = actorResolver.resolve(jwt, installId);
        String clientRequestId = clientRequestId(request.clientRequestId());
        int limit = limit(request.limit());
        optional(request.cursor(), "CURSOR", 4096);
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
            throw badRequest("INVALID_QUERY",
                    "query must contain between 1 and " + MAX_QUERY_LENGTH + " characters.");
        }
        StoredHttpResponse response = recommendationService.respondIdempotently(
                actor,
                "search",
                clientRequestId,
                request,
                () -> recommendationService.search(actor, query, request.cursor(), limit)
        );
        return stored(response);
    }

    @PostMapping(path = "/events:batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<?> events(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Install-Id", required = false) String installId,
            HttpServletRequest servletRequest,
            @RequestBody EventBatchRequest request
    ) {
        validateRequestSize(servletRequest);
        Actor actor = actorResolver.resolve(jwt, installId);
        if (request.events() == null
                || request.events().isEmpty()
                || request.events().size() > MAX_BATCH_SIZE) {
            throw badRequest("INVALID_BATCH_SIZE",
                    "events must contain between 1 and " + MAX_BATCH_SIZE + " entries.");
        }
        return success(eventService.process(actor, request.events()));
    }

    private static String clientRequestId(String value) {
        String id = value == null ? "" : value.trim();
        if (!SAFE_ID.matcher(id).matches()) {
            throw badRequest("INVALID_CLIENT_REQUEST_ID",
                    "clientRequestId must be 1-128 safe characters.");
        }
        return id;
    }

    private static int limit(Integer value) {
        int resolved = value == null ? DEFAULT_LIMIT : value;
        if (resolved < 1 || resolved > MAX_LIMIT) {
            throw badRequest("INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return resolved;
    }

    private static void optional(String value, String field, int maximumLength) {
        if (value != null && (value.isBlank() || value.length() > maximumLength)) {
            throw badRequest("INVALID_" + field,
                    field.toLowerCase().replace('_', ' ')
                            + " must be non-blank and at most " + maximumLength + " characters.");
        }
    }

    private static void validateRequestSize(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            throw new RecommendationException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "REQUEST_BODY_TOO_LARGE",
                    "Request body exceeds " + MAX_REQUEST_BYTES + " bytes."
            );
        }
    }

    private static ResponseEntity<String> stored(StoredHttpResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.json());
    }

    private static ApiEnvelope<Object> success(Object data) {
        return new ApiEnvelope<>(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", ""),
                System.currentTimeMillis(),
                data,
                null
        );
    }

    private static RecommendationException badRequest(String code, String message) {
        return new RecommendationException(HttpStatus.BAD_REQUEST, code, message);
    }
}
