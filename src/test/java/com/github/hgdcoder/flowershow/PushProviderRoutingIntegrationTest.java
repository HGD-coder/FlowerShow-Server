package com.github.hgdcoder.flowershow;

import com.github.hgdcoder.flowershow.push.FcmPushSender;
import com.github.hgdcoder.flowershow.push.PushDeliveryWorker;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_provider_routing_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.push.enabled=true",
        "flower-show.push.provider=xiaomi",
        "flower-show.push.schedule-interval=1h",
        "flower-show.push.batch-size=10",
        "flower-show.push.max-attempts=3",
        "flower-show.push.base-backoff=1ms",
        "flower-show.push.max-backoff=4ms",
        "flower-show.push.claim-timeout=1s",
        "flower-show.push.message-max-age=1h",
        "flower-show.push.android-channel-id=flower_show_notifications",
        "flower-show.push.notification-title=Flower Show"
})
class PushProviderRoutingIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PushDeliveryWorker worker;

    @SpyBean
    FcmPushSender fcmPushSender;

    @Test
    void unconfiguredMainlandProviderSkipsFcmAndBecomesProviderSpecificDead() {
        jdbcTemplate.update("""
                insert into users (id, nickname, source)
                values ('provider_routing_user', 'Provider Routing User', 'test')
                """);
        jdbcTemplate.update("""
                insert into user_device_tokens (
                    id, user_id, token, platform, recipient_type, push_provider, enabled
                ) values (
                    'provider_routing_device', 'provider_routing_user',
                    'provider-routing-token', 'android', 'token', 'huawei', true
                )
                """);
        jdbcTemplate.update("""
                insert into notifications (
                    id, receiver_user_id, type, message
                ) values (
                    'provider_routing_notification', 'provider_routing_user',
                    'new_post', 'Provider routing integration body'
                )
                """);
        jdbcTemplate.update("""
                insert into notification_deliveries (
                    id, notification_id, device_token_id
                ) values (
                    'provider_routing_delivery', 'provider_routing_notification',
                    'provider_routing_device'
                )
                """);

        assertEquals(1, worker.deliverOnce());

        Map<String, Object> delivery = jdbcTemplate.queryForMap("""
                select status, last_error
                from notification_deliveries
                where id = 'provider_routing_delivery'
                """);
        assertEquals("dead", delivery.get("status"));
        assertEquals("HUAWEI_PROVIDER_NOT_CONFIGURED", delivery.get("last_error"));
        assertTrue(jdbcTemplate.queryForObject("""
                select enabled
                from user_device_tokens
                where id = 'provider_routing_device'
                """, Boolean.class));
        verify(fcmPushSender, never()).send(any());
    }
}
