package com.github.hgdcoder.flowershow.push;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RoutingPushSender implements PushSender {

    private final Map<PushProvider, PushAdapter> adapters;

    public RoutingPushSender(List<PushAdapter> adapters) {
        Map<PushProvider, PushAdapter> adaptersByProvider = new EnumMap<>(PushProvider.class);
        for (PushAdapter adapter : adapters) {
            PushAdapter previous = adaptersByProvider.put(adapter.provider(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Multiple push adapters configured for " + adapter.provider().value() + "."
                );
            }
        }
        this.adapters = Map.copyOf(adaptersByProvider);
    }

    @Override
    public PushSendResult send(PushDeliveryMessage message) {
        PushAdapter adapter = adapters.get(message.provider());
        if (adapter == null) {
            return PushSendResult.permanent(PushErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        return adapter.send(message);
    }
}
