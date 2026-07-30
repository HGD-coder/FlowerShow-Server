package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.chat.ChatModels.SendMessageRequest;
import com.github.hgdcoder.flowershow.chat.ChatService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_chat_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.messaging.local-dispatcher-enabled=false",
        "flower-show.messaging.kafka.enabled=false",
        "flower-show.events.outbox.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class ChatIntegrationTest {

    private static final String USER_A = "chat_test_a";
    private static final String USER_B = "chat_test_b";
    private static final String USER_C = "chat_test_c";
    private static final String USER_D = "chat_test_d";
    private static final String USER_E = "chat_test_e";
    private static final String PUBLIC_VIDEO = "chat_public_video";
    private static final String PRIVATE_VIDEO = "chat_private_video";
    private static final String PUBLIC_IMAGE = "chat_public_image";
    private static final String DRAFT_VIDEO = "chat_draft_video";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ChatService chatService;

    @BeforeEach
    void resetChatFixtures() {
        jdbcTemplate.update("delete from chat_conversations");
        jdbcTemplate.update("""
                delete from outbox_events
                where event_type in (
                    'CHAT_MESSAGE_CREATED',
                    'CHAT_GROUP_OWNER_TRANSFERRED',
                    'CHAT_GROUP_DISSOLVED'
                )
                """);
        jdbcTemplate.update("""
                delete from follows
                where follower_id in (?, ?, ?, ?, ?)
                   or following_id in (?, ?, ?, ?, ?)
                """,
                USER_A, USER_B, USER_C, USER_D, USER_E,
                USER_A, USER_B, USER_C, USER_D, USER_E
        );
        jdbcTemplate.update("""
                delete from content_items
                where id in (?, ?, ?, ?)
                """, PUBLIC_VIDEO, PRIVATE_VIDEO, PUBLIC_IMAGE, DRAFT_VIDEO);
        ensureUser(USER_A, "Chat Alice");
        ensureUser(USER_B, "Chat Bob");
        ensureUser(USER_C, "Chat Carol");
        ensureUser(USER_D, "Chat Dave");
        ensureUser(USER_E, "Chat Eve");
        insertContent(PUBLIC_VIDEO, "video", "published", "public");
        insertContent(PRIVATE_VIDEO, "video", "published", "private");
        insertContent(PUBLIC_IMAGE, "image", "published", "public");
        insertContent(DRAFT_VIDEO, "video", "draft", "public");
    }

    @Test
    void directConversationRequiresMutualFollowIsUniqueAndMessageSendIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/chat/conversations/direct")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", USER_B))))
                .andExpect(status().isForbidden());

        follow(USER_A, USER_B);
        mockMvc.perform(post("/api/v1/chat/conversations/direct")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", USER_B))))
                .andExpect(status().isForbidden());

        follow(USER_B, USER_A);
        JsonNode created = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_B)
        );
        JsonNode duplicate = postJson(
                "/api/v1/chat/conversations/direct",
                USER_B,
                Map.of("userId", USER_A)
        );
        assertEquals(created.get("id").asText(), duplicate.get("id").asText());
        assertEquals("active", created.get("state").asText());
        assertTrue(created.get("dissolvedAt").isNull());
        assertTrue(created.get("dissolvedByUserId").isNull());
        assertEquals(1, count("select count(*) from chat_conversations"));

        String conversationId = created.get("id").asText();
        Map<String, Object> message = Map.of(
                "type", "text",
                "text", "hello from Alice",
                "clientMessageId", "direct-idempotency-1"
        );
        JsonNode first = postJson(
                "/api/v1/chat/conversations/" + conversationId + "/messages",
                USER_A,
                message
        );
        JsonNode retried = postJson(
                "/api/v1/chat/conversations/" + conversationId + "/messages",
                USER_A,
                message
        );
        assertEquals(first.get("id").asText(), retried.get("id").asText());
        assertEquals(1, count("select count(*) from chat_messages"));
        assertEquals(1, count("""
                select count(*) from outbox_events
                where event_type = 'CHAT_MESSAGE_CREATED'
                """));

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "type", "text",
                                "text", "different payload",
                                "clientMessageId", "direct-idempotency-1"
                        ))))
                .andExpect(status().isConflict());

        jdbcTemplate.update(
                "delete from follows where follower_id = ? and following_id = ?",
                USER_B,
                USER_A
        );
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "type", "text",
                                "text", "must be blocked",
                                "clientMessageId", "direct-blocked"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupOwnerControlsMembershipWhileMembersCanLeave() throws Exception {
        followBoth(USER_A, USER_B);
        mockMvc.perform(post("/api/v1/chat/conversations/group")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Blocked group",
                                "memberUserIds", List.of(USER_B, USER_C)
                        ))))
                .andExpect(status().isForbidden());
        assertEquals(0, count("select count(*) from chat_conversations"));

        JsonNode group = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of("name", "Garden friends", "memberUserIds", List.of(USER_B))
        );
        String conversationId = group.get("id").asText();
        assertEquals(2, group.get("members").size());

        mockMvc.perform(patch("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Not allowed"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renamed garden friends"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed garden friends"));

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/members")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userIds", List.of(USER_C)))))
                .andExpect(status().isForbidden());

        followBoth(USER_A, USER_C);
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/members")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userIds", List.of(USER_C)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(3));

        mockMvc.perform(delete("/api/v1/chat/conversations/" + conversationId + "/members/" + USER_C)
                        .with(jwtFor(USER_B)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/chat/conversations/" + conversationId + "/members/" + USER_B)
                        .with(jwtFor(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2));

        mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_B)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/leave")
                        .with(jwtFor(USER_C)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));

        mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_C)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/leave")
                        .with(jwtFor(USER_A)))
                .andExpect(status().isConflict());
    }

    @Test
    void conversationSummariesBatchGroupAvatarMembersInContractOrder() throws Exception {
        for (String memberId : List.of(USER_B, USER_C, USER_D, USER_E)) {
            followBoth(USER_A, memberId);
        }
        String groupId = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of(
                        "name", "Avatar members",
                        "memberUserIds", List.of(USER_B, USER_C, USER_D, USER_E)
                )
        ).get("id").asText();
        postJson(
                "/api/v1/chat/conversations/" + groupId + "/owner",
                USER_A,
                Map.of("userId", USER_D)
        );
        String directId = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_B)
        ).get("id").asText();

        JsonNode summaries = getJson("/api/v1/chat/conversations?pageSize=20", USER_A);
        JsonNode groupSummary = findSummary(summaries, groupId);
        assertEquals(List.of(USER_D, USER_A, USER_B, USER_C), avatarMemberIds(groupSummary));
        for (JsonNode member : groupSummary.get("avatarMembers")) {
            String userId = member.get("userId").asText();
            assertEquals(testAvatarUrl(userId), member.get("avatarUrl").asText());
        }
        JsonNode directSummary = findSummary(summaries, directId);
        assertEquals(0, directSummary.get("avatarMembers").size());
        assertEquals(testAvatarUrl(USER_B), directSummary.get("displayAvatarUrl").asText());

        mockMvc.perform(delete("/api/v1/chat/conversations/" + groupId + "/members/" + USER_B)
                        .with(jwtFor(USER_D)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/chat/conversations/" + groupId + "/leave")
                        .with(jwtFor(USER_E)))
                .andExpect(status().isOk());

        JsonNode updatedGroupSummary = findSummary(
                getJson("/api/v1/chat/conversations?pageSize=20", USER_A),
                groupId
        );
        assertEquals(List.of(USER_D, USER_A, USER_C), avatarMemberIds(updatedGroupSummary));
    }

    @Test
    void ownerTransferSwapsRolesAndAuthorityWithGuardrails() throws Exception {
        followBoth(USER_A, USER_B);
        followBoth(USER_A, USER_C);
        JsonNode group = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of("name", "Transfer group", "memberUserIds", List.of(USER_B, USER_C))
        );
        String conversationId = group.get("id").asText();
        String directId = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_B)
        ).get("id").asText();

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/owner")
                        .with(jwtFor(USER_C))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", USER_B))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/owner")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", USER_A))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/owner")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", USER_D))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/chat/conversations/" + directId + "/owner")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", USER_B))))
                .andExpect(status().isConflict());

        JsonNode transferred = postJson(
                "/api/v1/chat/conversations/" + conversationId + "/owner",
                USER_A,
                Map.of("userId", USER_B)
        );
        assertEquals(USER_B, transferred.get("ownerUserId").asText());
        assertEquals("active", transferred.get("state").asText());
        assertEquals("member", jdbcTemplate.queryForObject("""
                select member_role
                from chat_conversation_members
                where conversation_id = ? and user_id = ?
                """, String.class, conversationId, USER_A));
        assertEquals("owner", jdbcTemplate.queryForObject("""
                select member_role
                from chat_conversation_members
                where conversation_id = ? and user_id = ?
                """, String.class, conversationId, USER_B));
        assertEquals(USER_B, jdbcTemplate.queryForObject("""
                select owner_user_id
                from chat_conversations
                where id = ?
                """, String.class, conversationId));
        assertEquals(1, count("""
                select count(*)
                from outbox_events
                where event_type = 'CHAT_GROUP_OWNER_TRANSFERRED'
                  and aggregate_type = 'chat_conversation'
                  and aggregate_id = ?
                  and schema_version = 1
                  and payload_json like '%"eventVersion":1%'
                  and payload_json like '%"previousOwnerUserId":"chat_test_a"%'
                  and payload_json like '%"newOwnerUserId":"chat_test_b"%'
                """, conversationId));

        mockMvc.perform(patch("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Old owner denied"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "New owner authority"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New owner authority"));

        followBoth(USER_B, USER_D);
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/members")
                        .with(jwtFor(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userIds", List.of(USER_D)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(4));
        mockMvc.perform(delete("/api/v1/chat/conversations/" + conversationId + "/members/" + USER_D)
                        .with(jwtFor(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(3));
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/leave")
                        .with(jwtFor(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));

        postJson(
                "/api/v1/chat/conversations/" + conversationId + "/owner",
                USER_B,
                Map.of("userId", USER_C)
        );
        assertEquals(2, count("""
                select count(*)
                from outbox_events
                where event_type = 'CHAT_GROUP_OWNER_TRANSFERRED'
                  and aggregate_id = ?
                """, conversationId));
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/leave")
                        .with(jwtFor(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/dissolve")
                        .with(jwtFor(USER_C)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("dissolved"))
                .andExpect(jsonPath("$.dissolvedByUserId").value(USER_C));
    }

    @Test
    void dissolutionIsIdempotentReadPreservingAndBlocksEveryGroupMutation() throws Exception {
        followBoth(USER_A, USER_B);
        followBoth(USER_A, USER_C);
        String conversationId = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of("name", "Lifecycle group", "memberUserIds", List.of(USER_B))
        ).get("id").asText();
        JsonNode message = sendText(
                conversationId,
                USER_A,
                "Retained after dissolution",
                "dissolution-history"
        );
        String directId = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_C)
        ).get("id").asText();

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/dissolve")
                        .with(jwtFor(USER_B)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/chat/conversations/" + directId + "/dissolve")
                        .with(jwtFor(USER_A)))
                .andExpect(status().isConflict());

        JsonNode dissolved = postJson(
                "/api/v1/chat/conversations/" + conversationId + "/dissolve",
                USER_A,
                Map.of()
        );
        assertEquals("dissolved", dissolved.get("state").asText());
        assertEquals(USER_A, dissolved.get("dissolvedByUserId").asText());
        assertTrue(!dissolved.get("dissolvedAt").asText().isBlank());
        assertEquals(2, dissolved.get("members").size());
        String dissolvedAt = dissolved.get("dissolvedAt").asText();

        JsonNode retried = postJson(
                "/api/v1/chat/conversations/" + conversationId + "/dissolve",
                USER_A,
                Map.of()
        );
        assertEquals(dissolvedAt, retried.get("dissolvedAt").asText());
        assertEquals(1, count("""
                select count(*)
                from outbox_events
                where event_type = 'CHAT_GROUP_DISSOLVED'
                  and aggregate_type = 'chat_conversation'
                  and aggregate_id = ?
                  and schema_version = 1
                  and payload_json like '%"eventVersion":1%'
                  and payload_json like '%"dissolvedByUserId":"chat_test_a"%'
                  and payload_json like '%"dissolvedAt":%'
                """, conversationId));
        assertEquals(2, count("""
                select count(*)
                from chat_conversation_members
                where conversation_id = ? and member_state = 'active'
                """, conversationId));
        assertEquals(1, count("""
                select count(*)
                from chat_messages
                where conversation_id = ?
                """, conversationId));

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/dissolve")
                        .with(jwtFor(USER_B)))
                .andExpect(status().isForbidden());
        JsonNode summary = getJson("/api/v1/chat/conversations?pageSize=20", USER_B)
                .get("items").get(0);
        assertEquals(conversationId, summary.get("id").asText());
        assertEquals("dissolved", summary.get("state").asText());
        assertEquals(dissolvedAt, summary.get("dissolvedAt").asText());
        JsonNode detail = getJson(
                "/api/v1/chat/conversations/" + conversationId,
                USER_B
        );
        assertEquals(USER_A, detail.get("dissolvedByUserId").asText());
        JsonNode history = getJson(
                "/api/v1/chat/conversations/" + conversationId + "/messages",
                USER_B
        );
        assertEquals(message.get("id").asText(), history.get("items").get(0).get("id").asText());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/read")
                        .with(jwtFor(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadMessageId").value(message.get("id").asText()))
                .andExpect(jsonPath("$.unreadCount").value(0));

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .with(jwtFor(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "type", "text",
                                "text", "blocked",
                                "clientMessageId", "dissolved-send"
                        ))))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "blocked"))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/members")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userIds", List.of(USER_C)))))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/chat/conversations/" + conversationId + "/members/" + USER_B)
                        .with(jwtFor(USER_A)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/leave")
                        .with(jwtFor(USER_B)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/owner")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", USER_B))))
                .andExpect(status().isConflict());
    }

    @Test
    void lifecycleSchemaDefaultsAndConstraintsProtectConversationShape() throws Exception {
        followBoth(USER_A, USER_B);
        String groupId = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of("name", "Schema group", "memberUserIds", List.of(USER_B))
        ).get("id").asText();
        String directId = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_B)
        ).get("id").asText();

        assertEquals("active", jdbcTemplate.queryForObject("""
                select conversation_state
                from chat_conversations
                where id = ?
                """, String.class, groupId));
        assertEquals(2, count("""
                select count(*)
                from chat_conversations
                where id in (?, ?)
                  and conversation_state = 'active'
                  and dissolved_at is null
                  and dissolved_by_user_id is null
                """, groupId, directId));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                update chat_conversations
                set dissolved_at = current_timestamp, dissolved_by_user_id = ?
                where id = ?
                """, USER_A, groupId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                update chat_conversations
                set conversation_state = 'dissolved', dissolved_at = current_timestamp
                where id = ?
                """, groupId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                update chat_conversations
                set conversation_state = 'dissolved',
                    dissolved_at = current_timestamp,
                    dissolved_by_user_id = ?
                where id = ?
                """, USER_A, directId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                update chat_conversations
                set conversation_state = 'dissolved',
                    dissolved_at = current_timestamp,
                    dissolved_by_user_id = 'missing-user'
                where id = ?
                """, groupId));
    }

    @Test
    void historyVideoShareUnreadReadAndCursorsArePersistent() throws Exception {
        followBoth(USER_A, USER_B);
        String directId = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_B)
        ).get("id").asText();

        JsonNode first = sendText(directId, USER_A, "first", "history-1");
        JsonNode second = sendText(directId, USER_A, "second", "history-2");
        JsonNode third = postJson(
                "/api/v1/chat/conversations/" + directId + "/messages",
                USER_A,
                Map.of(
                        "type", "video_share",
                        "contentId", PUBLIC_VIDEO,
                        "clientMessageId", "history-3"
                )
        );
        assertMessageSender(first, USER_A, "Chat Alice", testAvatarUrl(USER_A));
        assertTrue(first.get("sharedContent").isNull());
        assertMessageSender(third, USER_A, "Chat Alice", testAvatarUrl(USER_A));
        assertEquals(PUBLIC_VIDEO, third.get("sharedContentId").asText());
        assertVideoPreview(third.get("sharedContent"), PUBLIC_VIDEO);

        for (String contentId : List.of(PRIVATE_VIDEO, PUBLIC_IMAGE, DRAFT_VIDEO, "missing-video")) {
            mockMvc.perform(post("/api/v1/chat/conversations/" + directId + "/messages")
                            .with(jwtFor(USER_A))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "type", "video_share",
                                    "contentId", contentId,
                                    "clientMessageId", "invalid-" + contentId
                            ))))
                    .andExpect(status().isBadRequest());
        }

        JsonNode list = getJson(
                "/api/v1/chat/conversations?pageSize=20",
                USER_B
        );
        assertEquals(3, list.get("items").get(0).get("unreadCount").asInt());
        JsonNode lastMessage = list.get("items").get(0).get("lastMessage");
        assertEquals(third, lastMessage);

        JsonNode firstHistoryPage = getJson(
                "/api/v1/chat/conversations/" + directId + "/messages?pageSize=2",
                USER_B
        );
        assertTrue(firstHistoryPage.get("hasMore").asBoolean());
        assertEquals(lastMessage, firstHistoryPage.get("items").get(0));
        assertEquals(second, firstHistoryPage.get("items").get(1));
        assertTrue(firstHistoryPage.get("items").get(1).get("sharedContent").isNull());
        JsonNode secondHistoryPage = getJson(
                "/api/v1/chat/conversations/" + directId + "/messages?pageSize=2&cursor="
                        + firstHistoryPage.get("nextCursor").asText(),
                USER_B
        );
        assertEquals(1, secondHistoryPage.get("items").size());
        assertEquals(first, secondHistoryPage.get("items").get(0));

        mockMvc.perform(post("/api/v1/chat/conversations/" + directId + "/read")
                        .with(jwtFor(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("messageId", second.get("id").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        mockMvc.perform(post("/api/v1/chat/conversations/" + directId + "/read")
                        .with(jwtFor(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadMessageId").value(third.get("id").asText()))
                .andExpect(jsonPath("$.unreadCount").value(0));

        postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of("name", "Cursor group", "memberUserIds", List.of(USER_B))
        );
        JsonNode firstConversationPage = getJson(
                "/api/v1/chat/conversations?pageSize=1",
                USER_B
        );
        assertTrue(firstConversationPage.get("hasMore").asBoolean());
        JsonNode secondConversationPage = getJson(
                "/api/v1/chat/conversations?pageSize=1&cursor="
                        + firstConversationPage.get("nextCursor").asText(),
                USER_B
        );
        assertEquals(1, secondConversationPage.get("items").size());
        assertNotEquals(
                firstConversationPage.get("items").get(0).get("id").asText(),
                secondConversationPage.get("items").get(0).get("id").asText()
        );

        assertEquals(3, count("""
                select count(*)
                from outbox_events
                where event_type = 'CHAT_MESSAGE_CREATED'
                  and aggregate_type = 'chat_conversation'
                  and aggregate_id = ?
                  and schema_version = 1
                  and payload_json like '%"eventVersion":1%'
                """, directId));
    }

    @Test
    void historicalSenderProfileUsesCurrentUserAfterMemberRemoval() throws Exception {
        followBoth(USER_A, USER_B);
        String groupId = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of("name", "Historical sender", "memberUserIds", List.of(USER_B))
        ).get("id").asText();

        JsonNode sent = sendText(
                groupId,
                USER_B,
                "message before removal",
                "former-member-message"
        );
        assertMessageSender(sent, USER_B, "Chat Bob", testAvatarUrl(USER_B));

        mockMvc.perform(delete("/api/v1/chat/conversations/" + groupId + "/members/" + USER_B)
                        .with(jwtFor(USER_A)))
                .andExpect(status().isOk());
        String updatedAvatarUrl = "https://cdn.example/former-chat-bob.jpg";
        jdbcTemplate.update(
                "update users set nickname = ?, avatar_url = ? where id = ?",
                "Former Chat Bob",
                updatedAvatarUrl,
                USER_B
        );

        JsonNode historyMessage = getJson(
                "/api/v1/chat/conversations/" + groupId + "/messages",
                USER_A
        ).get("items").get(0);
        assertMessageSender(historyMessage, USER_B, "Former Chat Bob", updatedAvatarUrl);
        assertTrue(historyMessage.get("sharedContent").isNull());

        JsonNode summaryLastMessage = getJson(
                "/api/v1/chat/conversations?pageSize=20",
                USER_A
        ).get("items").get(0).get("lastMessage");
        assertMessageSender(summaryLastMessage, USER_B, "Former Chat Bob", updatedAvatarUrl);
        assertEquals(historyMessage, summaryLastMessage);
    }

    @Test
    void nonMembersCannotReadSendOrMarkRead() throws Exception {
        followBoth(USER_A, USER_B);
        String conversationId = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_B)
        ).get("id").asText();
        sendText(conversationId, USER_A, "private message", "authorization-1");

        mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId)
                        .with(jwtFor(USER_D)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .with(jwtFor(USER_D)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .with(jwtFor(USER_D))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "type", "text",
                                "text", "intrusion",
                                "clientMessageId", "authorization-2"
                        ))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/read")
                        .with(jwtFor(USER_D)))
                .andExpect(status().isForbidden());
    }

    @Test
    void textAndTypeLengthLimitsApplyAtControllerAndServiceBoundaries() throws Exception {
        followBoth(USER_A, USER_B);
        String conversationId = postJson(
                "/api/v1/chat/conversations/direct",
                USER_A,
                Map.of("userId", USER_B)
        ).get("id").asText();

        String maximumText = "x".repeat(4000);
        sendText(conversationId, USER_A, maximumText, "text-limit-4000");
        assertEquals(4000, jdbcTemplate.queryForObject("""
                select length(body)
                from chat_messages
                where client_message_id = 'text-limit-4000'
                """, Integer.class));

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "type", "text",
                                "text", "x".repeat(4001),
                                "clientMessageId", "text-limit-4001"
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "type", "t".repeat(21),
                                "text", "invalid type",
                                "clientMessageId", "type-limit-21"
                        ))))
                .andExpect(status().isBadRequest());

        ResponseStatusException serviceError = assertThrows(
                ResponseStatusException.class,
                () -> chatService.send(
                        USER_A,
                        conversationId,
                        new SendMessageRequest(
                                "text",
                                "s".repeat(4001),
                                null,
                                "service-text-limit-4001"
                        )
                )
        );
        assertEquals(400, serviceError.getStatusCode().value());
        assertEquals(1, count("select count(*) from chat_messages"));
    }

    @Test
    void groupCapacityAllowsTwoHundredButRejectsEveryPathToTwoHundredOne() throws Exception {
        List<String> candidates = ensureCapacityUsers(200);

        mockMvc.perform(post("/api/v1/chat/conversations/group")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Too many at creation",
                                "memberUserIds", candidates
                        ))))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("select count(*) from chat_conversations"));

        JsonNode maximumCreateGroup = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of(
                        "name", "Maximum create group",
                        "memberUserIds", candidates.subList(0, 199)
                )
        );
        assertEquals(200, maximumCreateGroup.get("members").size());

        String incrementalGroupId = postJson(
                "/api/v1/chat/conversations/group",
                USER_A,
                Map.of("name", "Incremental capacity group")
        ).get("id").asText();
        postJson(
                "/api/v1/chat/conversations/" + incrementalGroupId + "/members",
                USER_A,
                Map.of("userIds", candidates.subList(0, 198))
        );
        JsonNode atMaximum = postJson(
                "/api/v1/chat/conversations/" + incrementalGroupId + "/members",
                USER_A,
                Map.of("userIds", List.of(candidates.get(0), candidates.get(198)))
        );
        assertEquals(200, atMaximum.get("members").size());

        mockMvc.perform(delete(
                        "/api/v1/chat/conversations/" + incrementalGroupId
                                + "/members/" + candidates.get(198)
                ).with(jwtFor(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(199));

        mockMvc.perform(post("/api/v1/chat/conversations/" + incrementalGroupId + "/members")
                        .with(jwtFor(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userIds",
                                List.of(candidates.get(0), candidates.get(198), candidates.get(199))
                        ))))
                .andExpect(status().isConflict());
        assertEquals("removed", jdbcTemplate.queryForObject("""
                select member_state
                from chat_conversation_members
                where conversation_id = ? and user_id = ?
                """, String.class, incrementalGroupId, candidates.get(198)));
        assertEquals(0, count("""
                select count(*)
                from chat_conversation_members
                where conversation_id = ? and user_id = ?
                """, incrementalGroupId, candidates.get(199)));

        JsonNode reactivatedAtMaximum = postJson(
                "/api/v1/chat/conversations/" + incrementalGroupId + "/members",
                USER_A,
                Map.of("userIds", List.of(candidates.get(0), candidates.get(198)))
        );
        assertEquals(200, reactivatedAtMaximum.get("members").size());

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/chat/conversations/" + incrementalGroupId + "/members")
                            .with(jwtFor(USER_A))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(candidates.get(199))))))
                    .andExpect(status().isConflict());
        }
        assertEquals(200, count("""
                select count(*)
                from chat_conversation_members
                where conversation_id = ? and member_state = 'active'
                """, incrementalGroupId));
    }

    private JsonNode sendText(
            String conversationId,
            String senderUserId,
            String text,
            String clientMessageId
    ) throws Exception {
        return postJson(
                "/api/v1/chat/conversations/" + conversationId + "/messages",
                senderUserId,
                Map.of(
                        "type", "text",
                        "text", text,
                        "clientMessageId", clientMessageId
                )
        );
    }

    private void assertMessageSender(
            JsonNode message,
            String userId,
            String nickname,
            String avatarUrl
    ) {
        assertEquals(userId, message.get("senderUserId").asText());
        assertEquals(nickname, message.get("senderNickname").asText());
        assertEquals(avatarUrl, message.get("senderAvatarUrl").asText());
    }

    private void assertVideoPreview(JsonNode sharedContent, String contentId) {
        assertEquals(contentId, sharedContent.get("id").asText());
        assertEquals("Chat test " + contentId, sharedContent.get("title").asText());
        assertEquals(testCoverUrl(contentId), sharedContent.get("coverUrl").asText());
        assertEquals(USER_A, sharedContent.get("authorUserId").asText());
        assertEquals("Chat Alice", sharedContent.get("authorNickname").asText());
    }

    private static JsonNode findSummary(JsonNode response, String conversationId) {
        for (JsonNode summary : response.get("items")) {
            if (conversationId.equals(summary.get("id").asText())) {
                return summary;
            }
        }
        throw new AssertionError("Conversation summary not found: " + conversationId);
    }

    private static List<String> avatarMemberIds(JsonNode summary) {
        List<String> userIds = new ArrayList<>();
        summary.get("avatarMembers").forEach(member ->
                userIds.add(member.get("userId").asText()));
        return userIds;
    }

    private JsonNode postJson(String path, String userId, Object body) throws Exception {
        String response = mockMvc.perform(post(path)
                        .with(jwtFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String path, String userId) throws Exception {
        String response = mockMvc.perform(get(path).with(jwtFor(userId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private void followBoth(String firstUserId, String secondUserId) {
        follow(firstUserId, secondUserId);
        follow(secondUserId, firstUserId);
    }

    private void follow(String followerId, String followingId) {
        jdbcTemplate.update("""
                insert into follows (follower_id, following_id)
                values (?, ?)
                """, followerId, followingId);
    }

    private List<String> ensureCapacityUsers(int count) {
        List<String> userIds = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String userId = "chat_capacity_" + String.format("%03d", index);
            ensureUser(userId, "Capacity User " + index);
            followBoth(USER_A, userId);
            userIds.add(userId);
        }
        return userIds;
    }

    private void ensureUser(String userId, String nickname) {
        String avatarUrl = testAvatarUrl(userId);
        jdbcTemplate.update("""
                insert into users (id, nickname, avatar_url, source)
                select ?, ?, ?, 'app'
                where not exists (select 1 from users where id = ?)
                """, userId, nickname, avatarUrl, userId);
        jdbcTemplate.update("""
                update users
                set nickname = ?, avatar_url = ?
                where id = ?
                """, nickname, avatarUrl, userId);
    }

    private void insertContent(String id, String type, String status, String visibility) {
        jdbcTemplate.update("""
                insert into content_items (
                    id, type, title, author_user_id, cover_url,
                    status, visibility, publish_time
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                type,
                "Chat test " + id,
                USER_A,
                testCoverUrl(id),
                status,
                visibility,
                InstantHolder.NOW
        );
    }

    private static String testAvatarUrl(String userId) {
        return "https://cdn.example/avatars/" + userId + ".jpg";
    }

    private static String testCoverUrl(String contentId) {
        return "https://cdn.example/covers/" + contentId + ".jpg";
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static RequestPostProcessor jwtFor(String userId) {
        return jwt().jwt(builder -> builder.subject(userId));
    }

    private static final class InstantHolder {
        private static final long NOW = System.currentTimeMillis() / 1000;
    }
}
