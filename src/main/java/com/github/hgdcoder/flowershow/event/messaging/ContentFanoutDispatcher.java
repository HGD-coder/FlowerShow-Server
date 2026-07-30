package com.github.hgdcoder.flowershow.event.messaging;

public interface ContentFanoutDispatcher {

    void dispatch(EventEnvelope event);
}
