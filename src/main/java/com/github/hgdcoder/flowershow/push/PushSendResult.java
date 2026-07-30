package com.github.hgdcoder.flowershow.push;

import java.util.Objects;

public record PushSendResult(
        Outcome outcome,
        String providerMessageId,
        PushErrorCode errorCode
) {
    public PushSendResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.SUCCESS) {
            if (providerMessageId == null || providerMessageId.isBlank()) {
                throw new IllegalArgumentException("Successful push result requires a provider message id.");
            }
            providerMessageId = providerMessageId.trim();
            errorCode = null;
        } else {
            Objects.requireNonNull(errorCode, "errorCode");
            providerMessageId = null;
        }
    }

    public static PushSendResult success(String providerMessageId) {
        return new PushSendResult(Outcome.SUCCESS, providerMessageId, null);
    }

    public static PushSendResult retryable(PushErrorCode errorCode) {
        return new PushSendResult(Outcome.RETRYABLE, null, errorCode);
    }

    public static PushSendResult permanent(PushErrorCode errorCode) {
        return new PushSendResult(Outcome.PERMANENT, null, errorCode);
    }

    public static PushSendResult invalidRecipient(PushErrorCode errorCode) {
        return new PushSendResult(Outcome.INVALID_RECIPIENT, null, errorCode);
    }

    public enum Outcome {
        SUCCESS,
        RETRYABLE,
        PERMANENT,
        INVALID_RECIPIENT
    }
}
