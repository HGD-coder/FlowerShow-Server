package com.github.hgdcoder.flowershow;

import com.github.hgdcoder.flowershow.push.PushDeliveryClaim;
import com.github.hgdcoder.flowershow.push.PushDeliveryMessage;
import com.github.hgdcoder.flowershow.push.PushDeliveryStore;
import com.github.hgdcoder.flowershow.push.PushDeliveryWorker;
import com.github.hgdcoder.flowershow.push.PushErrorCode;
import com.github.hgdcoder.flowershow.push.PushProvider;
import com.github.hgdcoder.flowershow.push.PushRecipientType;
import com.github.hgdcoder.flowershow.push.PushSendResult;
import com.github.hgdcoder.flowershow.push.PushSender;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_push_delivery_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.push.enabled=true",
        "flower-show.push.provider=fcm",
        "flower-show.push.schedule-interval=1h",
        "flower-show.push.batch-size=100",
        "flower-show.push.max-attempts=3",
        "flower-show.push.base-backoff=1ms",
        "flower-show.push.max-backoff=4ms",
        "flower-show.push.claim-timeout=1s",
        "flower-show.push.message-max-age=1h",
        "flower-show.push.android-channel-id=flower_show_notifications",
        "flower-show.push.notification-title=Flower Show"
})
@AutoConfigureMockMvc
class PushDeliveryWorkerIntegrationTest {

    private static final String USER_ID = "push_test_user";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PushDeliveryWorker worker;

    @Autowired
    PushDeliveryStore deliveryStore;

    @MockBean
    PushSender pushSender;

    @BeforeEach
    void prepareDatabase() {
        reset(pushSender);
        jdbcTemplate.update("""
                delete from notification_deliveries
                where device_token_id in (
                    select id from user_device_tokens where user_id = ?
                )
                """, USER_ID);
        jdbcTemplate.update("delete from user_device_tokens where user_id = ?", USER_ID);
        jdbcTemplate.update("delete from notifications where receiver_user_id = ?", USER_ID);
        jdbcTemplate.update("""
                insert into users (id, nickname, source)
                select ?, 'Push Test User', 'test'
                where not exists (select 1 from users where id = ?)
                """, USER_ID, USER_ID);
    }

    @Test
    void legacyDeviceRequestDefaultsToTokenWithoutReturningRecipient() throws Exception {
        mockMvc.perform(post("/api/v1/me/devices")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "legacy-registration-value",
                                  "platform": "android",
                                  "deviceName": "legacy-client"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientType").value("token"))
                .andExpect(jsonPath("$.pushProvider").value("fcm"))
                .andExpect(jsonPath("$.token").doesNotExist());

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select recipient_type, push_provider
                from user_device_tokens
                where user_id = ?
                """, USER_ID);
        assertEquals("token", row.get("recipient_type"));
        assertEquals("fcm", row.get("push_provider"));
    }

    @Test
    void mainlandProviderMetadataIsNormalizedReturnedAndListedWithoutRecipient() throws Exception {
        mockMvc.perform(post("/api/v1/me/devices")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": " huawei-registration-value ",
                                  "platform": " Android ",
                                  "deviceName": " Mate 70 ",
                                  "recipientType": " TOKEN ",
                                  "pushProvider": " HUAWEI ",
                                  "deviceBrand": " Huawei ",
                                  "appPackage": " com.example.flowershow "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("android"))
                .andExpect(jsonPath("$.deviceName").value("Mate 70"))
                .andExpect(jsonPath("$.recipientType").value("token"))
                .andExpect(jsonPath("$.pushProvider").value("huawei"))
                .andExpect(jsonPath("$.deviceBrand").value("Huawei"))
                .andExpect(jsonPath("$.appPackage").value("com.example.flowershow"))
                .andExpect(jsonPath("$.token").doesNotExist());

        mockMvc.perform(get("/api/v1/me/devices")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pushProvider").value("huawei"))
                .andExpect(jsonPath("$[0].deviceBrand").value("Huawei"))
                .andExpect(jsonPath("$[0].appPackage").value("com.example.flowershow"))
                .andExpect(jsonPath("$[0].token").doesNotExist());
    }

    @Test
    void mainlandProviderRejectsFidWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/me/devices")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "unsupported-huawei-fid",
                                  "platform": "android",
                                  "recipientType": "fid",
                                  "pushProvider": "huawei"
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertEquals(0, deviceCount());
    }

    @Test
    void sameTokenCanCoexistAcrossProvidersWithDifferentDeviceIds() throws Exception {
        String requestTemplate = """
                {
                  "token": "shared-provider-token",
                  "platform": "android",
                  "pushProvider": "%s"
                }
                """;
        mockMvc.perform(post("/api/v1/me/devices")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestTemplate.formatted("fcm")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/devices")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestTemplate.formatted("xiaomi")))
                .andExpect(status().isOk());

        assertEquals(2, deviceCount());
        assertEquals(2, jdbcTemplate.queryForObject("""
                select count(distinct id)
                from user_device_tokens
                where user_id = ? and token = ?
                """, Integer.class, USER_ID, "shared-provider-token"));
    }

    @Test
    void fidRegistrationReturnsTypeButNeverFidValue() throws Exception {
        mockMvc.perform(post("/api/v1/me/devices")
                        .with(jwt().jwt(builder -> builder.subject(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "firebase-installation-id-value",
                                  "platform": "android",
                                  "deviceName": "fid-client",
                                  "recipientType": "fid"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientType").value("fid"))
                .andExpect(jsonPath("$.token").doesNotExist());

        assertEquals("fid", jdbcTemplate.queryForObject(
                "select recipient_type from user_device_tokens where user_id = ?",
                String.class,
                USER_ID
        ));
    }

    @Test
    void successfulDeliveryPersistsProviderMessageIdAndBuildsRequiredPayload() {
        TestDelivery delivery = insertDelivery(Instant.now(), "fid", 0, "pending", null, null);
        when(pushSender.send(any())).thenReturn(
                PushSendResult.success("projects/test/messages/message-1")
        );

        assertEquals(1, worker.deliverOnce());

        Map<String, Object> row = deliveryRow(delivery.deliveryId());
        assertEquals("delivered", row.get("status"));
        assertEquals(1, ((Number) row.get("attempt_count")).intValue());
        assertEquals("projects/test/messages/message-1", row.get("provider_message_id"));
        assertNotNull(row.get("delivered_at"));
        assertNull(row.get("last_error"));

        ArgumentCaptor<PushDeliveryMessage> messageCaptor =
                ArgumentCaptor.forClass(PushDeliveryMessage.class);
        verify(pushSender).send(messageCaptor.capture());
        PushDeliveryMessage message = messageCaptor.getValue();
        assertEquals(PushProvider.FCM, message.provider());
        assertEquals(PushRecipientType.FID, message.recipientType());
        assertEquals(delivery.notificationId(), message.data().get("notificationId"));
        assertEquals("new_post", message.data().get("type"));
        assertEquals("Flower Show", message.title());
        assertEquals("Push integration body", message.body());
        assertEquals("flower_show_notifications", message.androidChannelId());
    }

    @Test
    void retryableFailureSucceedsOnSecondAttempt() {
        TestDelivery delivery = insertDelivery(Instant.now(), "token", 0, "pending", null, null);
        when(pushSender.send(any()))
                .thenReturn(PushSendResult.retryable(PushErrorCode.UNAVAILABLE))
                .thenReturn(PushSendResult.success("projects/test/messages/message-2"));

        assertEquals(1, worker.deliverOnce());
        Map<String, Object> retryRow = deliveryRow(delivery.deliveryId());
        assertEquals("retry", retryRow.get("status"));
        assertEquals(1, ((Number) retryRow.get("attempt_count")).intValue());
        assertEquals("FCM_UNAVAILABLE", retryRow.get("last_error"));

        makeReady(delivery.deliveryId());
        assertEquals(1, worker.deliverOnce());
        Map<String, Object> deliveredRow = deliveryRow(delivery.deliveryId());
        assertEquals("delivered", deliveredRow.get("status"));
        assertEquals(2, ((Number) deliveredRow.get("attempt_count")).intValue());
        verify(pushSender, times(2)).send(any());
    }

    @Test
    void retryableFailureAtAttemptLimitBecomesDead() {
        TestDelivery delivery = insertDelivery(Instant.now(), "token", 2, "retry", null, null);
        when(pushSender.send(any())).thenReturn(
                PushSendResult.retryable(PushErrorCode.QUOTA_EXCEEDED)
        );

        assertEquals(1, worker.deliverOnce());

        Map<String, Object> row = deliveryRow(delivery.deliveryId());
        assertEquals("dead", row.get("status"));
        assertEquals(3, ((Number) row.get("attempt_count")).intValue());
        assertEquals("FCM_QUOTA_EXCEEDED", row.get("last_error"));
        assertTrue(deviceEnabled(delivery.deviceId()));
    }

    @Test
    void invalidRecipientDisablesDeviceAndTerminatesItsRemainingQueue() {
        TestDelivery first = insertDelivery(Instant.now(), "token", 0, "pending", null, null);
        TestDelivery second = insertDeliveryForDevice(
                first.deviceId(),
                Instant.now(),
                0,
                "pending",
                null,
                null
        );
        when(pushSender.send(any())).thenReturn(
                PushSendResult.invalidRecipient(PushErrorCode.UNREGISTERED)
        );

        assertEquals(2, worker.deliverOnce());

        assertFalse(deviceEnabled(first.deviceId()));
        assertEquals("dead", deliveryRow(first.deliveryId()).get("status"));
        assertEquals("dead", deliveryRow(second.deliveryId()).get("status"));
        assertEquals("FCM_UNREGISTERED", deliveryRow(second.deliveryId()).get("last_error"));
        verify(pushSender, times(1)).send(any());
    }

    @Test
    void permanentFailureDoesNotDisableDevice() {
        TestDelivery delivery = insertDelivery(Instant.now(), "token", 0, "pending", null, null);
        when(pushSender.send(any())).thenReturn(
                PushSendResult.permanent(PushErrorCode.AUTHENTICATION)
        );

        assertEquals(1, worker.deliverOnce());

        assertEquals("dead", deliveryRow(delivery.deliveryId()).get("status"));
        assertEquals("FCM_AUTHENTICATION", deliveryRow(delivery.deliveryId()).get("last_error"));
        assertTrue(deviceEnabled(delivery.deviceId()));
    }

    @Test
    void providerNotConfiguredBecomesProviderSpecificDeadWithoutDisablingDevice() {
        TestDelivery delivery = insertDelivery(
                PushProvider.HUAWEI,
                Instant.now(),
                "token",
                0,
                "pending",
                null,
                null
        );
        when(pushSender.send(any())).thenReturn(
                PushSendResult.permanent(PushErrorCode.PROVIDER_NOT_CONFIGURED)
        );

        assertEquals(1, worker.deliverOnce());

        assertEquals("dead", deliveryRow(delivery.deliveryId()).get("status"));
        assertEquals(
                "HUAWEI_PROVIDER_NOT_CONFIGURED",
                deliveryRow(delivery.deliveryId()).get("last_error")
        );
        assertTrue(deviceEnabled(delivery.deviceId()));

        ArgumentCaptor<PushDeliveryMessage> messageCaptor =
                ArgumentCaptor.forClass(PushDeliveryMessage.class);
        verify(pushSender).send(messageCaptor.capture());
        assertEquals(PushProvider.HUAWEI, messageCaptor.getValue().provider());
    }

    @Test
    void expiredNotificationBecomesDeadWithoutCallingFirebase() {
        TestDelivery delivery = insertDelivery(
                Instant.now().minusSeconds(7200),
                "token",
                0,
                "pending",
                null,
                null
        );

        assertEquals(0, worker.deliverOnce());

        assertEquals("dead", deliveryRow(delivery.deliveryId()).get("status"));
        assertEquals("MESSAGE_EXPIRED", deliveryRow(delivery.deliveryId()).get("last_error"));
        verifyNoInteractions(pushSender);
    }

    @Test
    void timedOutClaimIsRecoveredAndDeliveredByANewLease() {
        Instant recoveryTime = Instant.now();
        TestDelivery delivery = insertDelivery(
                recoveryTime,
                "token",
                1,
                "processing",
                recoveryTime.minusSeconds(10),
                "abandoned-owner"
        );

        deliveryStore.maintain(recoveryTime);
        List<PushDeliveryClaim> recovered =
                deliveryStore.claimBatch("recovery-worker-owner", recoveryTime);
        assertEquals(1, recovered.size());
        assertEquals(PushProvider.FCM, recovered.get(0).provider());
        assertEquals(2, recovered.get(0).attemptCount());
        assertTrue(deliveryStore.complete(
                recovered.get(0),
                PushSendResult.success("projects/test/messages/message-after-timeout"),
                recoveryTime
        ));

        Map<String, Object> row = deliveryRow(delivery.deliveryId());
        assertEquals("delivered", row.get("status"));
        assertEquals(2, ((Number) row.get("attempt_count")).intValue());
    }

    @Test
    void staleOwnerCannotOverwriteDeliveryReclaimedByAnotherWorker() {
        TestDelivery delivery = insertDelivery(Instant.now(), "token", 0, "pending", null, null);
        Instant firstClaimTime = Instant.now();
        List<PushDeliveryClaim> firstClaims =
                deliveryStore.claimBatch("first-worker-owner", firstClaimTime);
        assertEquals(1, firstClaims.size());

        jdbcTemplate.update("""
                update notification_deliveries
                set claimed_at = ?
                where id = ?
                """, Timestamp.from(firstClaimTime.minusSeconds(10)), delivery.deliveryId());
        Instant secondClaimTime = firstClaimTime.plusSeconds(2);
        deliveryStore.maintain(secondClaimTime);
        List<PushDeliveryClaim> secondClaims =
                deliveryStore.claimBatch("second-worker-owner", secondClaimTime);
        assertEquals(1, secondClaims.size(), deliveryRow(delivery.deliveryId()).toString());

        assertFalse(deliveryStore.complete(
                firstClaims.get(0),
                PushSendResult.success("projects/test/messages/stale-result"),
                secondClaimTime
        ));
        Map<String, Object> processingRow = deliveryRow(delivery.deliveryId());
        assertEquals("processing", processingRow.get("status"));
        assertEquals("second-worker-owner", processingRow.get("claimed_by"));
        assertNull(processingRow.get("provider_message_id"));

        assertTrue(deliveryStore.complete(
                secondClaims.get(0),
                PushSendResult.success("projects/test/messages/current-result"),
                secondClaimTime
        ));
        assertEquals(
                "projects/test/messages/current-result",
                deliveryRow(delivery.deliveryId()).get("provider_message_id")
        );
    }

    private TestDelivery insertDelivery(
            Instant notificationCreatedAt,
            String recipientType,
            int attemptCount,
            String status,
            Instant claimedAt,
            String claimedBy
    ) {
        return insertDelivery(
                PushProvider.FCM,
                notificationCreatedAt,
                recipientType,
                attemptCount,
                status,
                claimedAt,
                claimedBy
        );
    }

    private TestDelivery insertDelivery(
            PushProvider provider,
            Instant notificationCreatedAt,
            String recipientType,
            int attemptCount,
            String status,
            Instant claimedAt,
            String claimedBy
    ) {
        int value = sequence.incrementAndGet();
        String deviceId = "push_test_device_" + value;
        jdbcTemplate.update("""
                insert into user_device_tokens (
                    id, user_id, token, platform, device_name, recipient_type,
                    push_provider, enabled
                ) values (?, ?, ?, 'android', 'push-test', ?, ?, true)
                """,
                deviceId,
                USER_ID,
                "push-test-recipient-" + value,
                recipientType,
                provider.value()
        );
        return insertDeliveryForDevice(
                deviceId,
                notificationCreatedAt,
                attemptCount,
                status,
                claimedAt,
                claimedBy
        );
    }

    private TestDelivery insertDeliveryForDevice(
            String deviceId,
            Instant notificationCreatedAt,
            int attemptCount,
            String status,
            Instant claimedAt,
            String claimedBy
    ) {
        int value = sequence.incrementAndGet();
        String notificationId = "push_test_notification_" + value;
        String deliveryId = "push_test_delivery_" + value;
        jdbcTemplate.update("""
                insert into notifications (
                    id, receiver_user_id, type, message, created_at
                ) values (?, ?, 'new_post', 'Push integration body', ?)
                """, notificationId, USER_ID, Timestamp.from(notificationCreatedAt));
        jdbcTemplate.update("""
                insert into notification_deliveries (
                    id, notification_id, device_token_id, status, attempt_count,
                    next_attempt_at, claimed_at, claimed_by
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                deliveryId,
                notificationId,
                deviceId,
                status,
                attemptCount,
                Timestamp.from(Instant.now().minusSeconds(1)),
                claimedAt == null ? null : Timestamp.from(claimedAt),
                claimedBy
        );
        return new TestDelivery(deliveryId, notificationId, deviceId);
    }

    private void makeReady(String deliveryId) {
        jdbcTemplate.update("""
                update notification_deliveries
                set next_attempt_at = ?
                where id = ?
                """, Timestamp.from(Instant.now().minusSeconds(1)), deliveryId);
    }

    private Map<String, Object> deliveryRow(String deliveryId) {
        return jdbcTemplate.queryForMap("""
                select status, attempt_count, claimed_at, claimed_by, delivered_at,
                       provider_message_id, last_error
                from notification_deliveries
                where id = ?
                """, deliveryId);
    }

    private boolean deviceEnabled(String deviceId) {
        Boolean enabled = jdbcTemplate.queryForObject(
                "select enabled from user_device_tokens where id = ?",
                Boolean.class,
                deviceId
        );
        assertNotNull(enabled);
        return enabled;
    }

    private int deviceCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from user_device_tokens where user_id = ?",
                Integer.class,
                USER_ID
        );
        assertNotNull(count);
        return count;
    }

    private record TestDelivery(String deliveryId, String notificationId, String deviceId) {
    }
}
