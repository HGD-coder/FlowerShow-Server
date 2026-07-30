package com.github.hgdcoder.flowershow.event;

@FunctionalInterface
public interface EventPublisher {

    void publish(DomainEvent event);
}