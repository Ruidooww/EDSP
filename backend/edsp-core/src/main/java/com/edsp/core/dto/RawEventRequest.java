package com.edsp.core.dto;

import jakarta.validation.constraints.NotBlank;

public record RawEventRequest(
    Long dataSourceId,
    Long taskId,
    Long runId,
    String sourceSystem,
    String externalId,
    String eventType,
    String occurredAt,
    @NotBlank String payloadJson,
    String payloadHash,
    String status
) {
    public RawEventRequest {
        if (status == null || status.isBlank()) {
            status = "received";
        }
        if (eventType == null || eventType.isBlank()) {
            eventType = "unknown";
        }
        if (sourceSystem == null || sourceSystem.isBlank()) {
            sourceSystem = "external";
        }
    }
}
