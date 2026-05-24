package com.edsp.alert.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NotificationChannelAdapterRegistry {
    private final Map<String, NotificationChannelAdapter> adapters;

    public NotificationChannelAdapterRegistry(List<NotificationChannelAdapter> adapters) {
        this.adapters = adapters.stream()
            .collect(Collectors.toUnmodifiableMap(
                adapter -> normalize(adapter.channelType()),
                Function.identity()
            ));
    }

    public Optional<NotificationChannelAdapter> find(String channelType) {
        return Optional.ofNullable(adapters.get(normalize(channelType)));
    }

    private String normalize(String channelType) {
        return channelType == null ? "" : channelType.trim().toLowerCase(Locale.ROOT);
    }
}
