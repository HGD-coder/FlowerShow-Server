package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.model.NotificationDto;
import com.github.hgdcoder.flowershow.service.NotificationService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_viewer_contract_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.events.outbox.fixed-delay-ms=600000",
        "flower-show.events.outbox.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class ContentViewerInteractionNotificationIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NotificationService notificationService;

    @Test
    void cardsExposeAuthorAndPersistentViewerStateWithoutChangingVideoUserId() throws Exception {
        String viewerUserId = "seed_u004";

        postInteraction("/api/v1/contents/img001/like", viewerUserId);
        postInteraction("/api/v1/contents/img001/favorite", viewerUserId);

        JsonNode publicFeed = getJson("/api/v1/feed?page=1&pageSize=50", null);
        Set<String> types = new HashSet<>();
        for (JsonNode item : publicFeed) {
            types.add(item.path("type").asText());
            assertTrue(item.hasNonNull("authorUserId"));
            assertTrue(item.has("likedByViewer"));
            assertTrue(item.has("favoritedByViewer"));
            assertFalse(item.path("likedByViewer").asBoolean());
            assertFalse(item.path("favoritedByViewer").asBoolean());
            if ("video".equals(item.path("type").asText())) {
                assertTrue(item.hasNonNull("userId"));
                assertEquals(item.path("userId").asText(), item.path("authorUserId").asText());
            }
        }
        assertEquals(Set.of("video", "image", "album"), types);

        assertViewerState(getJson("/api/v1/feed?page=1&pageSize=50", viewerUserId), "img001");
        assertViewerState(
                getJson("/api/v1/users/u001/profile/contents?tab=posts&page=1&pageSize=50", viewerUserId)
                        .path("items"),
                "img001"
        );
        assertViewerState(getJson("/api/v1/users/u001/contents", viewerUserId), "img001");
        assertViewerState(getJson("/api/v1/search?keyword=garden", viewerUserId), "img001");

        JsonNode videos = getJson("/api/v1/videos?page=1&pageSize=50", viewerUserId);
        assertFalse(videos.isEmpty());
        for (JsonNode video : videos) {
            assertEquals(video.path("userId").asText(), video.path("authorUserId").asText());
            assertTrue(video.has("likedByViewer"));
            assertTrue(video.has("favoritedByViewer"));
        }
    }

    @Test
    void deleteLikeAndFavoriteUseJwtSubjectAndRemainIdempotentAndNonNegative() throws Exception {
        String contentId = "v004";
        String retainedUserId = "seed_u004";
        String deletingUserId = "seed_u005";

        jdbcTemplate.update("delete from content_likes where content_id = ? and user_id in (?, ?)",
                contentId, retainedUserId, deletingUserId);
        jdbcTemplate.update("insert into content_likes (content_id, user_id) values (?, ?)",
                contentId, retainedUserId);
        jdbcTemplate.update("insert into content_likes (content_id, user_id) values (?, ?)",
                contentId, deletingUserId);
        jdbcTemplate.update("update content_stats set like_count = 2 where content_id = ?", contentId);

        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/like").with(jwtFor(deletingUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.stats.likeCount").value(1));
        assertEquals(1, count("""
                select count(*) from content_likes
                where content_id = ? and user_id = ?
                """, contentId, retainedUserId));
        assertEquals(0, count("""
                select count(*) from content_likes
                where content_id = ? and user_id = ?
                """, contentId, deletingUserId));

        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/like").with(jwtFor(deletingUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.stats.likeCount").value(1));

        jdbcTemplate.update("insert into content_likes (content_id, user_id) values (?, ?)",
                contentId, deletingUserId);
        jdbcTemplate.update("update content_stats set like_count = 0 where content_id = ?", contentId);
        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/like").with(jwtFor(deletingUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.stats.likeCount").value(0));

        String retainedFavoriteUserId = "seed_u001";
        String deletingFavoriteUserId = "seed_u002";
        String retainedCollectionId = "col_seed_u001_default";
        String deletingCollectionId = "col_seed_u002_default";
        jdbcTemplate.update("insert into collection_items (collection_id, content_id) values (?, ?)",
                retainedCollectionId, contentId);
        jdbcTemplate.update("insert into collection_items (collection_id, content_id) values (?, ?)",
                deletingCollectionId, contentId);
        jdbcTemplate.update("update content_stats set favorite_count = 2 where content_id = ?", contentId);

        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/favorite").with(jwtFor(deletingFavoriteUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.stats.favoriteCount").value(1));
        assertEquals(1, count("""
                select count(*)
                from collection_items item
                join collections collection on collection.id = item.collection_id
                where item.content_id = ? and collection.user_id = ?
                """, contentId, retainedFavoriteUserId));
        assertEquals(0, count("""
                select count(*)
                from collection_items item
                join collections collection on collection.id = item.collection_id
                where item.content_id = ? and collection.user_id = ?
                """, contentId, deletingFavoriteUserId));

        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/favorite").with(jwtFor(deletingFavoriteUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.stats.favoriteCount").value(1));

        jdbcTemplate.update("insert into collection_items (collection_id, content_id) values (?, ?)",
                deletingCollectionId, contentId);
        jdbcTemplate.update("update content_stats set favorite_count = 0 where content_id = ?", contentId);
        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/favorite").with(jwtFor(deletingFavoriteUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.stats.favoriteCount").value(0));

        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/like"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/contents/" + contentId + "/favorite"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/contents/v003/like")
                        .with(jwtFor(deletingUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"seed_u004\"}"))
                .andExpect(status().isForbidden());
        postInteraction("/api/v1/contents/v003/like", deletingUserId);
    }

    @Test
    void notificationsIncludeActorDetailsAndKeepNullActorRows() throws Exception {
        String receiverUserId = "seed_u003";
        jdbcTemplate.update("""
                insert into notifications (
                    id, receiver_user_id, actor_user_id, type, message, created_at
                ) values (
                    'ntf_contract_actor', ?, 'u003', 'contract_actor',
                    'Actor details contract notification', current_timestamp
                )
                """, receiverUserId);
        jdbcTemplate.update("""
                insert into notifications (
                    id, receiver_user_id, actor_user_id, type, message, created_at
                ) values (
                    'ntf_contract_system', ?, null, 'contract_system',
                    'System contract notification', current_timestamp
                )
                """, receiverUserId);
        jdbcTemplate.update("""
                insert into users (id, handle, nickname, avatar_url, source)
                values (
                    'usr_contract_deleted_actor', 'contract_deleted_actor',
                    'Deleted Actor', 'https://example.test/deleted-actor.png', 'app'
                )
                """);
        jdbcTemplate.update("""
                insert into notifications (
                    id, receiver_user_id, actor_user_id, type, message, created_at
                ) values (
                    'ntf_contract_deleted_actor', ?, 'usr_contract_deleted_actor',
                    'contract_deleted_actor', 'Deleted actor contract notification',
                    current_timestamp
                )
                """, receiverUserId);
        jdbcTemplate.update("delete from users where id = 'usr_contract_deleted_actor'");

        JsonNode items = getJson("/api/v1/me/notifications?pageSize=50", receiverUserId).path("items");
        JsonNode actorNotification = findById(items, "ntf_contract_actor");
        assertEquals("u003", actorNotification.path("actorUserId").asText());
        assertEquals("Bloom Care", actorNotification.path("actorNickname").asText());
        assertEquals(
                "https://picsum.photos/200/200?random=3",
                actorNotification.path("actorAvatarUrl").asText()
        );

        JsonNode systemNotification = findById(items, "ntf_contract_system");
        assertFalse(systemNotification.has("actorUserId"));
        assertFalse(systemNotification.has("actorNickname"));
        assertFalse(systemNotification.has("actorAvatarUrl"));

        JsonNode deletedActorNotification = findById(items, "ntf_contract_deleted_actor");
        assertFalse(deletedActorNotification.has("actorUserId"));
        assertFalse(deletedActorNotification.has("actorNickname"));
        assertFalse(deletedActorNotification.has("actorAvatarUrl"));

        List<NotificationDto> notifications = notificationService.list(receiverUserId);
        NotificationDto systemDto = notifications.stream()
                .filter(notification -> notification.id().equals("ntf_contract_system"))
                .findFirst()
                .orElseThrow();
        assertNull(systemDto.actorUserId());
        assertNull(systemDto.actorNickname());
        assertNull(systemDto.actorAvatarUrl());
    }

    private void postInteraction(String path, String userId) throws Exception {
        mockMvc.perform(post(path)
                        .with(jwtFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("userId", userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    private JsonNode getJson(String path, String userId) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        if (userId != null) {
            request.with(jwtFor(userId));
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void assertViewerState(JsonNode items, String contentId) {
        JsonNode item = findById(items, contentId);
        assertTrue(item.path("likedByViewer").asBoolean());
        assertTrue(item.path("favoritedByViewer").asBoolean());
    }

    private JsonNode findById(JsonNode items, String id) {
        assertTrue(items.isArray());
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Response does not contain id " + id + ": " + items);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    private static RequestPostProcessor jwtFor(String userId) {
        return jwt().jwt(builder -> builder.subject(userId));
    }
}
