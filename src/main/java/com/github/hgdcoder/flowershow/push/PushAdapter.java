package com.github.hgdcoder.flowershow.push;

public interface PushAdapter {

    PushProvider provider();

    PushSendResult send(PushDeliveryMessage message);
}
