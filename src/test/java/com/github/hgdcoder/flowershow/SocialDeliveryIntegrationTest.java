package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.event.outbox.LocalOutboxDispatchOperation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_social_delivery_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.messaging.local-dispatcher-enabled=true",
        "flower-show.messaging.kafka.enabled=false",
        "flower-show.events.outbox.initial-delay-ms=600000",
        "flower-show.feed.fanout-on-write-max-followers=0",
        "flower-show.feed.fanout-chunk-size=100"
})
@AutoConfigureMockMvc
class SocialDeliveryIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LocalOutboxDispatchOperation outboxDispatchOperation;

    @Test
    void outboxFanoutHybridFeedNotificationsAndPreferencesFormAClosedLoop() throws Exception {
        String followerId = "seed_u002";
        String authorId = "u003";

        mockMvc.perform(post("/api/v1/me/devices")
                        .with(jwtFor(followerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "test-fcm-token-social-delivery",
                                  "platform": "android",
                                  "deviceName": "integration-test"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("android"));

        mockMvc.perform(patch("/api/v1/users/seed_u002/following/u003/preferences")
                        .with(jwtFor(followerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyNewContent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifyNewContent").value(true));

        String pullContentId = publishVideo(authorId, "Pull-mode delivery video");
        assertTrue(outboxDispatchOperation.dispatchNext());
        String pullStatus = jdbcTemplate.queryForObject(
                "select status from outbox_events where aggregate_id = ?",
                String.class,
                pullContentId
        );
        String pullError = jdbcTemplate.queryForObject(
                "select coalesce(last_error, '') from outbox_events where aggregate_id = ?",
                String.class,
                pullContentId
        );
        assertEquals("processed", pullStatus, pullError);
        assertEquals(1, count("select count(*) from notifications where receiver_user_id = 'seed_u002'"));
        assertEquals(0, count("""
                select count(*) from user_feed_entries
                where user_id = 'seed_u002' and content_id = ?
                """, pullContentId));

        JsonNode pullFeed = getJson("/api/v1/feed/following?pageSize=10", followerId);
        assertTrue(itemIds(pullFeed).contains(pullContentId));

        jdbcTemplate.update("update users set fanout_mode = 'push' where id = 'u003'");
        String pushContentId = publishVideo(authorId, "Push-mode delivery video");
        assertTrue(outboxDispatchOperation.dispatchNext());
        assertEquals(1, count("""
                select count(*) from user_feed_entries
                where user_id = 'seed_u002' and content_id = ?
                """, pushContentId));

        JsonNode firstPage = getJson("/api/v1/feed/following?pageSize=1", followerId);
        assertTrue(firstPage.get("hasMore").asBoolean());
        String firstContentId = firstPage.get("items").get(0).get("id").asText();
        String cursor = firstPage.get("nextCursor").asText();
        JsonNode secondPage = getJson("/api/v1/feed/following?pageSize=1&cursor=" + cursor, followerId);
        String secondContentId = secondPage.get("items").get(0).get("id").asText();
        assertNotEquals(firstContentId, secondContentId);

        JsonNode notifications = getJson("/api/v1/me/notifications?pageSize=10", followerId);
        assertEquals(2, notifications.get("items").size());
        assertEquals(Set.of("new_post"), StreamSupport.stream(
                        notifications.get("items").spliterator(), false)
                .map(item -> item.get("type").asText())
                .collect(Collectors.toSet()));
        JsonNode firstNotificationPage = getJson("/api/v1/me/notifications?pageSize=1", followerId);
        String firstNotificationId = firstNotificationPage.get("items").get(0).get("id").asText();
        JsonNode secondNotificationPage = getJson(
                "/api/v1/me/notifications?pageSize=1&cursor="
                        + firstNotificationPage.get("nextCursor").asText(),
                followerId
        );
        assertNotEquals(
                firstNotificationId,
                secondNotificationPage.get("items").get(0).get("id").asText()
        );
        mockMvc.perform(get("/api/v1/me/notifications/unread-count")
                        .with(jwtFor(followerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
        assertEquals(2, count("select count(*) from notification_deliveries"));

        String pushEventId = jdbcTemplate.queryForObject(
                "select id from outbox_events where aggregate_id = ?",
                String.class,
                pushContentId
        );
        assertNotNull(pushEventId);
        jdbcTemplate.update("""
                update outbox_events
                set status = 'pending', processed_at = null, next_attempt_at = current_timestamp
                where id = ?
                """, pushEventId);
        assertTrue(outboxDispatchOperation.dispatchNext());
        assertEquals(2, count("select count(*) from notifications where receiver_user_id = 'seed_u002'"));
        assertEquals(1, count("""
                select count(*) from user_feed_entries
                where user_id = 'seed_u002' and content_id = ?
                """, pushContentId));

        mockMvc.perform(post("/api/v1/me/notifications/read-all")
                        .with(jwtFor(followerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changedCount").value(2))
                .andExpect(jsonPath("$.unreadCount").value(0));

        mockMvc.perform(patch("/api/v1/users/seed_u002/following/u003/preferences")
                        .with(jwtFor(followerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted").value(true));
        assertTrue(itemIds(getJson("/api/v1/feed/following?pageSize=10", followerId)).isEmpty());

        mockMvc.perform(patch("/api/v1/users/seed_u002/following/u003/preferences")
                        .with(jwtFor(followerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":false}"))
                .andExpect(status().isOk());
        assertFalse(itemIds(getJson("/api/v1/feed/following?pageSize=10", followerId)).isEmpty());

        mockMvc.perform(post("/api/v1/users/seed_u002/following/u003")
                        .with(jwtFor(followerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));

        String longFollowerId = "usr_11111111111111111111111111111111";
        String longAuthorId = "usr_22222222222222222222222222222222";
        createUserWithCounters(longFollowerId, "Long Follower");
        createUserWithCounters(longAuthorId, "Long Author");
        mockMvc.perform(post("/api/v1/users/" + longFollowerId + "/following/" + longAuthorId)
                        .with(jwtFor(longFollowerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
        assertEquals(73, jdbcTemplate.queryForObject("""
                select length(aggregate_id)
                from outbox_events
                where aggregate_id = ?
                """, Integer.class, longFollowerId + ":" + longAuthorId));
    }

    private String publishVideo(String authorId, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/contents")
                        .with(jwtFor(authorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "video",
                                "authorUserId", "u003",
                                "title", title,
                                "visibility", "public",
                                "mediaUrls", List.of("https://cdn.example.test/video.mp4")
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private JsonNode getJson(String path, String userId) throws Exception {
        String response = mockMvc.perform(get(path).with(jwtFor(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private Set<String> itemIds(JsonNode response) {
        return StreamSupport.stream(response.get("items").spliterator(), false)
                .map(item -> item.get("id").asText())
                .collect(Collectors.toSet());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void createUserWithCounters(String userId, String nickname) {
        jdbcTemplate.update("""
                insert into users (id, handle, nickname, source)
                values (?, ?, ?, 'app')
                """, userId, userId, nickname);
        jdbcTemplate.update("insert into user_social_stats (user_id) values (?)", userId);
        jdbcTemplate.update("insert into notification_unread_stats (user_id) values (?)", userId);
    }

    private static RequestPostProcessor jwtFor(String userId) {
        return jwt().jwt(builder -> builder.subject(userId));
    }
}
