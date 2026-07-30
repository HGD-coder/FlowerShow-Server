package com.github.hgdcoder.flowershow.push;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoutingPushSenderTest {

    @Test
    void routesFcmMessageToFcmAdapter() {
        FcmPushSender fcmAdapter = mock(FcmPushSender.class);
        when(fcmAdapter.provider()).thenReturn(PushProvider.FCM);
        RoutingPushSender sender = new RoutingPushSender(List.of(fcmAdapter));
        clearInvocations(fcmAdapter);
        PushDeliveryMessage message = message(PushProvider.FCM);
        PushSendResult expected = PushSendResult.success("projects/test/messages/routed");
        when(fcmAdapter.send(message)).thenReturn(expected);

        assertEquals(expected, sender.send(message));

        verify(fcmAdapter).send(message);
    }

    @Test
    void missingProviderDoesNotCallFcmAndReturnsPermanentNotConfigured() {
        FcmPushSender fcmAdapter = mock(FcmPushSender.class);
        when(fcmAdapter.provider()).thenReturn(PushProvider.FCM);
        RoutingPushSender sender = new RoutingPushSender(List.of(fcmAdapter));
        clearInvocations(fcmAdapter);

        PushSendResult result = sender.send(message(PushProvider.HUAWEI));

        assertEquals(PushSendResult.Outcome.PERMANENT, result.outcome());
        assertEquals(PushErrorCode.PROVIDER_NOT_CONFIGURED, result.errorCode());
        verifyNoInteractions(fcmAdapter);
    }

    private static PushDeliveryMessage message(PushProvider provider) {
        return new PushDeliveryMessage(
                provider,
                "recipient-value",
                PushRecipientType.TOKEN,
                "notification-id",
                "new_post",
                "Flower Show",
                "A new post is available.",
                "actor-id",
                "content-id",
                null,
                "flower_show_notifications"
        );
    }
}
