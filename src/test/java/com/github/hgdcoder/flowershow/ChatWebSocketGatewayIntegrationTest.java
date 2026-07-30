package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.chat.ChatModels.ChatMessageDto;
import com.github.hgdcoder.flowershow.chat.ChatModels.SendMessageRequest;
import com.github.hgdcoder.flowershow.chat.ChatService;
import com.github.hgdcoder.flowershow.chat.realtime.ChatWebSocketGateway;
import com.github.hgdcoder.flowershow.config.JwtProperties;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.event.messaging.DomainEventConsumeOperation;
import com.github.hgdcoder.flowershow.event.messaging.EventEnvelope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_chat_websocket_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.chat.websocket.enabled=true",
        "flower-show.chat.websocket.port=0",
        "flower-show.chat.websocket.idle-timeout=10s",
        "flower-show.messaging.local-dispatcher-enabled=false",
        "flower-show.messaging.kafka.enabled=false",
        "flower-show.events.outbox.initial-delay-ms=600000"
})
class ChatWebSocketGatewayIntegrationTest {

    private static final String USER_A = "chat_ws_a";
    private static final String USER_B = "chat_ws_b";
    private static final String USER_C = "chat_ws_c";
    private static final String CONVERSATION_ID = "con_chat_ws";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Autowired
    ChatWebSocketGateway gateway;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ChatService chatService;

    @Autowired
    DomainEventConsumeOperation domainEventConsumeOperation;

    @Autowired
    ObjectMapper objectMapper;

    private final List<WebSocket> sockets = new ArrayList<>();

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("delete from chat_conversations");
        jdbcTemplate.update("delete from consumer_processed_events where consumer_name = ?",
                "domain-event-handler-v1");
        jdbcTemplate.update("delete from outbox_events where event_type = ?",
                EventTypes.CHAT_MESSAGE_CREATED);
        ensureUser(USER_A, "WebSocket Alice");
        ensureUser(USER_B, "WebSocket Bob");
        ensureUser(USER_C, "WebSocket Carol");
    }

    @AfterEach
    void closeSockets() {
        for (WebSocket socket : sockets) {
            if (!socket.isOutputClosed()) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete")
                            .get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    socket.abort();
                }
            }
        }
        sockets.clear();
    }

    @Test
    void handshakeRejectsMissingAndInvalidJwtAcceptsValidJwtAndOnlyAllowsAck() throws Exception {
        assertHandshakeRejected(null, 401);
        assertHandshakeRejected("Bearer not-a-jwt", 401);

        TestConnection accepted = connect(USER_A);
        assertEquals("connection.ready", accepted.awaitJson(Duration.ofSeconds(2)).get("type").asText());

        accepted.webSocket().sendText(
                "{\"type\":\"ack\",\"eventId\":\"evt-accepted\"}",
                true
        ).join();
        assertThrows(
                TimeoutException.class,
                () -> accepted.listener().closed().get(200, TimeUnit.MILLISECONDS)
        );
        assertFalse(accepted.webSocket().isInputClosed());

        TestConnection businessWrite = connect(USER_A);
        assertEquals(
                "connection.ready",
                businessWrite.awaitJson(Duration.ofSeconds(2)).get("type").asText()
        );
        businessWrite.webSocket().sendText(
                "{\"type\":\"chat.message.send\",\"text\":\"not allowed\"}",
                true
        ).join();
        assertEquals(
                1008,
                businessWrite.listener().closed().get(2, TimeUnit.SECONDS).statusCode()
        );
    }

    @Test
    void connectionClosesWhenAccessTokenExpires() throws Exception {
        TestWebSocketListener listener = new TestWebSocketListener();
        WebSocket socket = HTTP_CLIENT.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token(USER_A, Duration.ofSeconds(1)))
                .buildAsync(webSocketUri(), listener)
                .join();
        sockets.add(socket);
        TestConnection connection = new TestConnection(socket, listener, objectMapper);

        assertEquals(
                "connection.ready",
                connection.awaitJson(Duration.ofSeconds(2)).get("type").asText()
        );
        CloseResult closed = listener.closed().get(3, TimeUnit.SECONDS);
        assertEquals(4001, closed.statusCode());
        assertEquals("Access token expired.", closed.reason());
    }

    @Test
    void eventUsesRestMessageShapeBroadcastsToEveryActiveMemberDeviceAndDeduplicatesReplay()
            throws Exception {
        createGroupWithActiveMembers();
        ChatMessageDto message = chatService.send(
                USER_A,
                CONVERSATION_ID,
                new SendMessageRequest("text", "live hello", null, "ws-client-message-1")
        );

        OutboxRow outbox = jdbcTemplate.queryForObject("""
                select id, aggregate_type, aggregate_id, partition_key,
                       schema_version, payload_json, occurred_at
                from outbox_events
                where event_type = ?
                """, (rs, rowNum) -> new OutboxRow(
                rs.getString("id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("partition_key"),
                rs.getInt("schema_version"),
                rs.getString("payload_json"),
                rs.getTimestamp("occurred_at")
        ), EventTypes.CHAT_MESSAGE_CREATED);

        JsonNode payloadJson = objectMapper.readTree(outbox.payloadJson());
        assertEquals(1, payloadJson.get("eventVersion").asInt());
        assertEquals(CONVERSATION_ID, payloadJson.get("conversationId").asText());
        assertEquals(message.id(), payloadJson.get("messageId").asText());
        assertEquals(objectMapper.valueToTree(message), payloadJson.get("message"));

        TestConnection alicePhone = connect(USER_A);
        TestConnection aliceTablet = connect(USER_A);
        TestConnection bobPhone = connect(USER_B);
        TestConnection removedCarol = connect(USER_C);
        awaitReady(alicePhone, aliceTablet, bobPhone, removedCarol);

        jdbcTemplate.update("""
                update chat_conversation_members
                set member_state = 'removed', left_at = current_timestamp
                where conversation_id = ? and user_id = ?
                """, CONVERSATION_ID, USER_C);

        Map<String, Object> payload = objectMapper.readValue(
                outbox.payloadJson(),
                new TypeReference<>() {
                }
        );
        EventEnvelope event = new EventEnvelope(
                outbox.id(),
                EventTypes.CHAT_MESSAGE_CREATED,
                outbox.aggregateType(),
                outbox.aggregateId(),
                outbox.partitionKey(),
                outbox.schemaVersion(),
                payload,
                outbox.occurredAt().toInstant()
        );
        domainEventConsumeOperation.consume(event);

        JsonNode alicePhoneEvent = assertMessageEvent(
                alicePhone.awaitJson(Duration.ofSeconds(2)),
                event,
                message
        );
        JsonNode aliceTabletEvent = assertMessageEvent(
                aliceTablet.awaitJson(Duration.ofSeconds(2)),
                event,
                message
        );
        JsonNode bobPhoneEvent = assertMessageEvent(
                bobPhone.awaitJson(Duration.ofSeconds(2)),
                event,
                message
        );
        assertEquals(alicePhoneEvent, aliceTabletEvent);
        assertEquals(alicePhoneEvent, bobPhoneEvent);
        assertNull(removedCarol.awaitJson(Duration.ofMillis(300)));

        domainEventConsumeOperation.consume(event);
        assertNull(alicePhone.awaitJson(Duration.ofMillis(300)));
        assertNull(aliceTablet.awaitJson(Duration.ofMillis(300)));
        assertNull(bobPhone.awaitJson(Duration.ofMillis(300)));
    }

    private JsonNode assertMessageEvent(
            JsonNode actual,
            EventEnvelope event,
            ChatMessageDto message
    ) {
        Set<String> fields = new HashSet<>();
        actual.fieldNames().forEachRemaining(fields::add);
        assertEquals(
                Set.of("type", "eventId", "occurredAt", "message"),
                fields
        );
        assertEquals("chat.message.created", actual.get("type").asText());
        assertEquals(event.eventId(), actual.get("eventId").asText());
        assertEquals(event.occurredAt(), Instant.parse(actual.get("occurredAt").asText()));
        assertEquals(objectMapper.valueToTree(message), actual.get("message"));
        return actual;
    }

    private void awaitReady(TestConnection... connections) throws Exception {
        for (TestConnection connection : connections) {
            JsonNode ready = connection.awaitJson(Duration.ofSeconds(2));
            assertEquals("connection.ready", ready.get("type").asText());
        }
    }

    private TestConnection connect(String userId) {
        TestWebSocketListener listener = new TestWebSocketListener();
        WebSocket socket = HTTP_CLIENT.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token(userId))
                .buildAsync(webSocketUri(), listener)
                .join();
        sockets.add(socket);
        return new TestConnection(socket, listener, objectMapper);
    }

    private void assertHandshakeRejected(String authorization, int expectedStatus) {
        WebSocket.Builder builder = HTTP_CLIENT.newWebSocketBuilder();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> builder.buildAsync(webSocketUri(), new TestWebSocketListener()).join()
        );
        Throwable cause = failure.getCause();
        assertInstanceOf(WebSocketHandshakeException.class, cause);
        assertEquals(
                expectedStatus,
                ((WebSocketHandshakeException) cause).getResponse().statusCode()
        );
    }

    private URI webSocketUri() {
        assertTrue(gateway.localPort() > 0);
        return URI.create("ws://127.0.0.1:" + gateway.localPort() + "/ws/chat");
    }

    private String token(String userId) {
        return token(userId, Duration.ofSeconds(60));
    }

    private String token(String userId, Duration lifetime) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .subject(userId)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }

    private void createGroupWithActiveMembers() {
        jdbcTemplate.update("""
                insert into chat_conversations (
                    id, conversation_type, name, owner_user_id
                )
                values (?, 'group', 'WebSocket test group', ?)
                """, CONVERSATION_ID, USER_A);
        jdbcTemplate.update("""
                insert into chat_conversation_members (
                    conversation_id, user_id, member_role
                )
                values (?, ?, 'owner'), (?, ?, 'member'), (?, ?, 'member')
                """,
                CONVERSATION_ID, USER_A,
                CONVERSATION_ID, USER_B,
                CONVERSATION_ID, USER_C
        );
    }

    private void ensureUser(String userId, String nickname) {
        jdbcTemplate.update("""
                insert into users (id, nickname, avatar_url, source)
                select ?, ?, ?, 'app'
                where not exists (select 1 from users where id = ?)
                """, userId, nickname, "https://cdn.example/avatars/" + userId + ".jpg", userId);
    }

    private record OutboxRow(
            String id,
            String aggregateType,
            String aggregateId,
            String partitionKey,
            int schemaVersion,
            String payloadJson,
            Timestamp occurredAt
    ) {
    }

    private record CloseResult(int statusCode, String reason) {
    }

    private record TestConnection(
            WebSocket webSocket,
            TestWebSocketListener listener,
            ObjectMapper objectMapper
    ) {
        JsonNode awaitJson(Duration timeout) throws Exception {
            String text = listener.texts().poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return text == null ? null : objectMapper.readTree(text);
        }
    }

    private static final class TestWebSocketListener implements WebSocket.Listener {

        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseResult> closed = new CompletableFuture<>();
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            textBuffer.append(data);
            if (last) {
                texts.add(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last
        ) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(new CloseResult(statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }

        BlockingQueue<String> texts() {
            return texts;
        }

        CompletableFuture<CloseResult> closed() {
            return closed;
        }
    }
}
