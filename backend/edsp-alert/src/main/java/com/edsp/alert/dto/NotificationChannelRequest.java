package com.edsp.alert.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record NotificationChannelRequest(
    @NotBlank String name,
    String channelType,
    String webhookUrl,
    String description,
    Boolean enabled,
    Map<String, Object> config
) {
    public NotificationChannelRequest {
        if (channelType == null || channelType.isBlank()) {
            channelType = "webhook";
        }
        if (enabled == null) {
            enabled = true;
        }
        if (config == null) {
            config = Map.of();
        }
    }
}
