package com.github.hgdcoder.flowershow.push;

public interface PushSender {

    PushSendResult send(PushDeliveryMessage message);
}
