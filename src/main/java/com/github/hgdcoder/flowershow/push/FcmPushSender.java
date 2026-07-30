package com.github.hgdcoder.flowershow.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.github.hgdcoder.flowershow.config.PushProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FcmPushSender implements PushAdapter {

    private final PushProperties properties;
    private final Object initializationMonitor = new Object();
    private volatile FirebaseApp firebaseApp;
    private volatile FirebaseMessaging firebaseMessaging;

    public FcmPushSender(PushProperties properties) {
        this.properties = properties;
    }

    @Override
    public PushProvider provider() {
        return PushProvider.FCM;
    }

    @Override
    public PushSendResult send(PushDeliveryMessage delivery) {
        if (delivery.provider() != PushProvider.FCM) {
            return PushSendResult.permanent(PushErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        try {
            Message.Builder builder = Message.builder()
                    .setNotification(Notification.builder()
                            .setTitle(delivery.title())
                            .setBody(delivery.body())
                            .build())
                    .putAllData(delivery.data())
                    .setAndroidConfig(AndroidConfig.builder()
                            .setNotification(AndroidNotification.builder()
                                    .setChannelId(delivery.androidChannelId())
                                    .build())
                            .build());
            if (delivery.recipientType() == PushRecipientType.FID) {
                builder.setFid(delivery.recipient());
            } else {
                builder.setToken(delivery.recipient());
            }
            return PushSendResult.success(messaging().send(builder.build()));
        } catch (FirebaseMessagingException error) {
            return classify(error);
        } catch (IOException error) {
            return PushSendResult.permanent(PushErrorCode.CREDENTIALS);
        } catch (IllegalArgumentException error) {
            return PushSendResult.permanent(PushErrorCode.INVALID_ARGUMENT);
        } catch (IllegalStateException error) {
            return PushSendResult.permanent(PushErrorCode.CONFIGURATION);
        } catch (RuntimeException error) {
            return PushSendResult.retryable(PushErrorCode.TRANSPORT_ERROR);
        }
    }

    @PreDestroy
    void closeFirebaseApp() {
        FirebaseApp app = firebaseApp;
        if (app != null) {
            app.delete();
        }
    }

    private FirebaseMessaging messaging() throws IOException {
        FirebaseMessaging current = firebaseMessaging;
        if (current != null) {
            return current;
        }
        synchronized (initializationMonitor) {
            current = firebaseMessaging;
            if (current != null) {
                return current;
            }
            FirebaseOptions.Builder options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault());
            if (properties.projectId() != null) {
                options.setProjectId(properties.projectId());
            }
            FirebaseApp app = FirebaseApp.initializeApp(
                    options.build(),
                    "flower-show-push-" + UUID.randomUUID()
            );
            try {
                current = FirebaseMessaging.getInstance(app);
                firebaseApp = app;
                firebaseMessaging = current;
                return current;
            } catch (RuntimeException error) {
                app.delete();
                throw error;
            }
        }
    }

    private static PushSendResult classify(FirebaseMessagingException error) {
        MessagingErrorCode messagingCode = error.getMessagingErrorCode();
        if (messagingCode != null) {
            return switch (messagingCode) {
                case UNREGISTERED ->
                        PushSendResult.invalidRecipient(PushErrorCode.UNREGISTERED);
                case SENDER_ID_MISMATCH ->
                        PushSendResult.invalidRecipient(PushErrorCode.SENDER_ID_MISMATCH);
                case QUOTA_EXCEEDED ->
                        PushSendResult.retryable(PushErrorCode.QUOTA_EXCEEDED);
                case UNAVAILABLE ->
                        PushSendResult.retryable(PushErrorCode.UNAVAILABLE);
                case INTERNAL ->
                        PushSendResult.retryable(PushErrorCode.INTERNAL);
                case INVALID_ARGUMENT ->
                        PushSendResult.permanent(PushErrorCode.INVALID_ARGUMENT);
                case THIRD_PARTY_AUTH_ERROR ->
                        PushSendResult.permanent(PushErrorCode.THIRD_PARTY_AUTH);
            };
        }
        return classifyPlatformError(error.getErrorCode());
    }

    private static PushSendResult classifyPlatformError(ErrorCode errorCode) {
        if (errorCode == null) {
            return PushSendResult.retryable(PushErrorCode.TRANSPORT_ERROR);
        }
        return switch (errorCode.name()) {
            case "RESOURCE_EXHAUSTED" ->
                    PushSendResult.retryable(PushErrorCode.QUOTA_EXCEEDED);
            case "UNAVAILABLE", "DEADLINE_EXCEEDED", "ABORTED", "CANCELLED", "UNKNOWN" ->
                    PushSendResult.retryable(PushErrorCode.UNAVAILABLE);
            case "INTERNAL" ->
                    PushSendResult.retryable(PushErrorCode.INTERNAL);
            case "UNAUTHENTICATED" ->
                    PushSendResult.permanent(PushErrorCode.AUTHENTICATION);
            case "PERMISSION_DENIED" ->
                    PushSendResult.permanent(PushErrorCode.PERMISSION_DENIED);
            case "INVALID_ARGUMENT" ->
                    PushSendResult.permanent(PushErrorCode.INVALID_ARGUMENT);
            default ->
                    PushSendResult.permanent(PushErrorCode.PERMANENT_FCM_ERROR);
        };
    }
}
