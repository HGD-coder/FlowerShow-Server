package com.github.hgdcoder.flowershow.event.messaging;

public record FanoutRecipient(
        String userId,
        boolean addToFeed,
        boolean notifyNewContent
) {
}
