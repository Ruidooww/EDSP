package com.edsp.alert.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record NotificationSendRequest(
    List<Long> channelIds,
    Long alertId,
    @NotBlank String title,
    @NotBlank String message,
    String severity,
    Map<String, Object> detail
) {
    public NotificationSendRequest {
        if (severity == null || severity.isBlank()) {
            severity = "info";
        }
        if (detail == null) {
            detail = Map.of();
        }
    }
}
