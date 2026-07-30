package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_recommendation_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.events.outbox.fixed-delay-ms=600000",
        "flower-show.recommendation.session-ttl=30m",
        "flower-show.recommendation.idempotency-ttl=24h"
})
@AutoConfigureMockMvc
class RecommendationApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void addIneligibleAndSearchFixtureContent() {
        insertContentIfMissing("rec_public_video", "video", "Rose balcony laboratory", "published", "public");
        insertContentIfMissing("rec_private_video", "video", "Never expose private video", "published", "private");
        insertContentIfMissing("rec_draft_video", "video", "Never expose draft video", "draft", "public");
        insertContentIfMissing("rec_public_image", "image", "Never expose image", "published", "public");
        insertTagIfMissing("rec_public_video", "rose");
        insertWordIfMissing("rec_public_video", "rose laboratory");
    }

    @Test
    void feedUsesOrganicEnvelopeAndOnlyEligibleDatabaseVideos() throws Exception {
        MvcResult result = postJson("/api/v1/feed/pages", "install-organic", Map.of(
                "clientRequestId", "feed-organic",
                "limit", 50
        )).andExpect(status().isOk()).andReturn();

        JsonNode envelope = json(result);
        assertEnvelopeHasExplicitNullError(result, envelope);
        assertEquals("organic", envelope.path("data").path("serveMode").asText());
        assertEquals("organic-hybrid-v3", envelope.path("data").path("algoVersion").asText());
        assertEquals("organic-only-v1", envelope.path("data").path("policyVersion").asText());

        Set<String> ids = new HashSet<>();
        for (JsonNode item : envelope.path("data").path("items")) {
            assertEquals("organic", item.path("deliveryType").asText());
            assertEquals("organic-only-v1", item.path("policyVersion").asText());
            assertEquals("video", item.path("payload").path("type").asText());
            assertFalse(item.path("exposureToken").asText().isBlank());
            ids.add(item.path("payload").path("id").asText());
        }
        assertTrue(ids.contains("rec_public_video"));
        assertFalse(ids.contains("rec_private_video"));
        assertFalse(ids.contains("rec_draft_video"));
        assertFalse(ids.contains("rec_public_image"));
    }

    @Test
    void feedSnapshotIsStableAcrossPagesWithoutDuplicates() throws Exception {
        JsonNode first = json(postJson("/api/v1/feed/pages", "install-pages", Map.of(
                "clientRequestId", "page-one",
                "limit", 2
        )).andExpect(status().isOk()).andReturn()).path("data");
        assertTrue(first.path("hasMore").asBoolean());

        JsonNode second = json(postJson("/api/v1/feed/pages", "install-pages", Map.of(
                "clientRequestId", "page-two",
                "serveSessionId", first.path("serveSessionId").asText(),
                "cursor", first.path("nextCursor").asText(),
                "limit", 2
        )).andExpect(status().isOk()).andReturn()).path("data");

        assertEquals(first.path("serveSessionId").asText(), second.path("serveSessionId").asText());
        Set<String> ids = new HashSet<>();
        first.path("items").forEach(item -> ids.add(item.path("id").asText()));
        second.path("items").forEach(item ->
                assertTrue(ids.add(item.path("id").asText()), "snapshot pages must not repeat entities"));
    }

    @Test
    void cursorIsSignedAndBoundToActorSessionAndSearchQuery() throws Exception {
        JsonNode feed = json(postJson("/api/v1/feed/pages", "install-cursor-a", Map.of(
                "clientRequestId", "actor-a-first",
                "limit", 1
        )).andExpect(status().isOk()).andReturn()).path("data");

        MvcResult wrongActor = postJson("/api/v1/feed/pages", "install-cursor-b", Map.of(
                "clientRequestId", "actor-b-next",
                "serveSessionId", feed.path("serveSessionId").asText(),
                "cursor", feed.path("nextCursor").asText(),
                "limit", 1
        )).andExpect(status().isBadRequest()).andReturn();
        assertErrorEnvelope(wrongActor, "CURSOR_ACTOR_MISMATCH");

        String tampered = mutate(feed.path("nextCursor").asText());
        MvcResult tamperResult = postJson("/api/v1/feed/pages", "install-cursor-a", Map.of(
                "clientRequestId", "tampered-cursor",
                "serveSessionId", feed.path("serveSessionId").asText(),
                "cursor", tampered,
                "limit", 1
        )).andExpect(status().isBadRequest()).andReturn();
        assertErrorEnvelope(tamperResult, "INVALID_CURSOR");

        JsonNode search = json(postJson("/api/v1/search/videos", "install-search-cursor", Map.of(
                "clientRequestId", "search-plant-one",
                "query", "plant",
                "limit", 1
        )).andExpect(status().isOk()).andReturn()).path("data");
        assertTrue(search.path("hasMore").asBoolean());
        MvcResult wrongQuery = postJson("/api/v1/search/videos", "install-search-cursor", Map.of(
                "clientRequestId", "search-query-reuse",
                "query", "rose",
                "cursor", search.path("nextCursor").asText(),
                "limit", 1
        )).andExpect(status().isBadRequest()).andReturn();
        assertErrorEnvelope(wrongQuery, "CURSOR_QUERY_MISMATCH");
    }

    @Test
    void clientRequestIdReplaysSameEnvelopeAndRejectsConflictingBody() throws Exception {
        Map<String, Object> body = Map.of("clientRequestId", "same-request", "limit", 3);
        String first = postJson("/api/v1/feed/pages", "install-idempotency", body)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = postJson("/api/v1/feed/pages", "install-idempotency", body)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);

        MvcResult conflict = postJson("/api/v1/feed/pages", "install-idempotency", Map.of(
                "clientRequestId", "same-request",
                "limit", 4
        )).andExpect(status().isConflict()).andReturn();
        assertErrorEnvelope(conflict, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void serverGeneratesGuessesAndSearchFromEligibleDatabaseMetadata() throws Exception {
        JsonNode guesses = json(postJson("/api/v1/search/guesses", "install-search", Map.of(
                "clientRequestId", "guess-db",
                "limit", 50
        )).andExpect(status().isOk()).andReturn()).path("data");
        List<String> texts = new ArrayList<>();
        guesses.path("items").forEach(item -> texts.add(item.path("text").asText()));
        assertTrue(texts.contains("rose laboratory"));

        JsonNode search = json(postJson("/api/v1/search/videos", "install-search", Map.of(
                "clientRequestId", "search-db",
                "query", "rose laboratory",
                "limit", 20
        )).andExpect(status().isOk()).andReturn()).path("data");
        assertEquals("rec_public_video", search.path("items").get(0).path("payload").path("id").asText());
        assertEquals("organic", search.path("items").get(0).path("deliveryType").asText());
    }

    @Test
    void eventsAreValidatedDeduplicatedPublishedAndChangeNextSessionPersonalization() throws Exception {
        JsonNode guessesBefore = json(postJson("/api/v1/search/guesses", "install-events", Map.of(
                "clientRequestId", "guess-before",
                "limit", 50,
                "refresh", true
        )).andExpect(status().isOk()).andReturn()).path("data");
        JsonNode target = guessesBefore.path("items").get(guessesBefore.path("items").size() - 1);
        String targetText = target.path("text").asText();

        long now = Instant.now().toEpochMilli();
        Map<String, Object> event = Map.of(
                "eventId", "event-suggestion-click",
                "type", "suggestion_click",
                "occurredAtMs", now,
                "exposureToken", target.path("exposureToken").asText(),
                "suggestionId", target.path("id").asText()
        );
        JsonNode accepted = json(postJson("/api/v1/events:batch", "install-events", Map.of(
                "events", List.of(event)
        )).andExpect(status().isOk()).andReturn());
        assertEquals("accepted", accepted.path("data").path("results").get(0).path("status").asText());

        JsonNode duplicate = json(postJson("/api/v1/events:batch", "install-events", Map.of(
                "events", List.of(event)
        )).andExpect(status().isOk()).andReturn());
        assertEquals("duplicate", duplicate.path("data").path("results").get(0).path("status").asText());

        Map<String, Object> tampered = Map.of(
                "eventId", "event-tampered",
                "type", "suggestion_click",
                "occurredAtMs", now,
                "exposureToken", mutate(target.path("exposureToken").asText()),
                "suggestionId", target.path("id").asText()
        );
        JsonNode rejected = json(postJson("/api/v1/events:batch", "install-events", Map.of(
                "events", List.of(tampered)
        )).andExpect(status().isOk()).andReturn());
        assertEquals("rejected", rejected.path("data").path("results").get(0).path("status").asText());
        assertEquals("INVALID_EXPOSURE_TOKEN", rejected.path("data").path("results").get(0).path("code").asText());

        JsonNode guessesAfter = json(postJson("/api/v1/search/guesses", "install-events", Map.of(
                "clientRequestId", "guess-after",
                "limit", 1,
                "refresh", true
        )).andExpect(status().isOk()).andReturn()).path("data");
        assertEquals(targetText, guessesAfter.path("items").get(0).path("text").asText());

        Integer eventRows = jdbcTemplate.queryForObject(
                "select count(*) from recommendation_client_events where event_id = ?",
                Integer.class,
                "event-suggestion-click"
        );
        Integer outboxRows = jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where event_type = 'RECOMMENDATION_BEHAVIOR'",
                Integer.class
        );
        assertEquals(1, eventRows);
        assertNotNull(outboxRows);
        assertTrue(outboxRows > 0);
    }

    @Test
    void anonymousRequiresInstallIdWhileVerifiedJwtUsesStableSubject() throws Exception {
        MvcResult missingActor = mockMvc.perform(post("/api/v1/feed/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientRequestId", "missing-install",
                                "limit", 1
                        ))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(missingActor, "INSTALL_ID_REQUIRED");

        mockMvc.perform(post("/api/v1/feed/pages")
                        .with(jwt().jwt(jwt -> jwt.subject("seed_u001")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientRequestId", "jwt-actor",
                                "limit", 1
                        ))))
                .andExpect(status().isOk());

        String storedActor = jdbcTemplate.queryForObject(
                "select actor_key from recommendation_serve_sessions where kind = 'feed' order by created_at desc limit 1",
                String.class
        );
        assertNotNull(storedActor);
        assertEquals(64, storedActor.length());
        assertNotEquals("seed_u001", storedActor);
    }

    @Test
    void invalidBearerOnRecommendationRouteStillUsesRecommendationEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/feed/pages")
                        .header("Authorization", "Bearer not-a-valid-jwt")
                        .header("X-Install-Id", "must-not-fallback-to-install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientRequestId", "invalid-bearer",
                                "limit", 1
                        ))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, "UNAUTHORIZED");
    }

    @Test
    void requestLimitsReturnDedicatedErrorEnvelopeAndLegacyGetsStillWork() throws Exception {
        MvcResult invalidLimit = postJson("/api/v1/feed/pages", "install-limits", Map.of(
                "clientRequestId", "invalid-limit",
                "limit", 51
        )).andExpect(status().isBadRequest()).andReturn();
        assertErrorEnvelope(invalidLimit, "INVALID_LIMIT");

        MvcResult invalidQuery = postJson("/api/v1/search/videos", "install-limits", Map.of(
                "clientRequestId", "invalid-query",
                "query", "x".repeat(201),
                "limit", 10
        )).andExpect(status().isBadRequest()).andReturn();
        assertErrorEnvelope(invalidQuery, "INVALID_QUERY");

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(Map.of("eventId", "batch-" + i, "type", "search_submit",
                    "occurredAtMs", Instant.now().toEpochMilli(), "query", "rose"));
        }
        MvcResult invalidBatch = postJson("/api/v1/events:batch", "install-limits", Map.of("events", tooMany))
                .andExpect(status().isBadRequest()).andReturn();
        assertErrorEnvelope(invalidBatch, "INVALID_BATCH_SIZE");

        mockMvc.perform(get("/api/v1/feed")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search").param("keyword", "rose")).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(
            String path,
            String installId,
            Object body
    ) throws Exception {
        return mockMvc.perform(post(path)
                .header("X-Install-Id", installId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertEnvelopeHasExplicitNullError(MvcResult result, JsonNode envelope) throws Exception {
        assertTrue(envelope.hasNonNull("requestId"));
        assertTrue(envelope.hasNonNull("traceId"));
        assertTrue(envelope.hasNonNull("serverTimeMs"));
        assertTrue(envelope.hasNonNull("data"));
        assertTrue(envelope.has("error"), result.getResponse().getContentAsString());
        assertTrue(envelope.get("error").isNull(), result.getResponse().getContentAsString());
    }

    private void assertErrorEnvelope(MvcResult result, String code) throws Exception {
        JsonNode envelope = json(result);
        assertTrue(envelope.has("data"), result.getResponse().getContentAsString());
        assertTrue(envelope.get("data").isNull(), result.getResponse().getContentAsString());
        assertTrue(envelope.hasNonNull("error"));
        assertEquals(code, envelope.path("error").path("code").asText());
    }

    private void insertContentIfMissing(String id, String type, String title, String status, String visibility) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from content_items where id = ?",
                Integer.class,
                id
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into content_items (
                    id, type, title, description, author_user_id, cover_url,
                    status, visibility, publish_time
                ) values (?, ?, ?, ?, 'u001', ?, ?, ?, ?)
                """,
                id, type, title, title, "https://example.test/" + id + ".jpg",
                status, visibility, Instant.now().getEpochSecond()
        );
        jdbcTemplate.update("""
                insert into content_stats (
                    content_id, like_count, comment_count, favorite_count, share_count, view_count
                ) values (?, 1, 1, 1, 1, 1)
                """, id);
        jdbcTemplate.update("""
                insert into media_assets (
                    content_id, kind, url, quality, sort_order
                ) values (?, ?, ?, '720p', 0)
                """, id, "video".equals(type) ? "video" : "image", "https://example.test/" + id + ".mp4");
    }

    private void insertTagIfMissing(String contentId, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from content_tags where content_id = ? and tag = ?",
                Integer.class,
                contentId,
                value
        );
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "insert into content_tags (content_id, tag, sort_order) values (?, ?, 0)",
                    contentId,
                    value
            );
        }
    }

    private void insertWordIfMissing(String contentId, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from content_recommend_words where content_id = ? and word = ?",
                Integer.class,
                contentId,
                value
        );
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "insert into content_recommend_words (content_id, word, sort_order) values (?, ?, 0)",
                    contentId,
                    value
            );
        }
    }

    private static String mutate(String value) {
        char last = value.charAt(value.length() - 1);
        return value.substring(0, value.length() - 1) + (last == 'a' ? 'b' : 'a');
    }
}
