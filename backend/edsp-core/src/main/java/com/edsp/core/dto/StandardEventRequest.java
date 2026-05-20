package com.edsp.core.dto;

import jakarta.validation.constraints.NotBlank;

public record StandardEventRequest(
    Long rawEventId,
    Long rawLogId,
    Long rawImportId,
    Long dataSourceId,
    @NotBlank String sourceSystem,
    String externalId,
    @NotBlank String eventType,
    String occurredAt,
    String actor,
    String assetRef,
    String subjectType,
    String subjectRef,
    String action,
    String result,
    String severity,
    Integer riskScore,
    String normalizedJson,
    String extraJson
) {
    public StandardEventRequest {
        if (severity == null || severity.isBlank()) {
            severity = "info";
        }
        if (riskScore == null) {
            riskScore = 0;
        }
        if (normalizedJson == null || normalizedJson.isBlank()) {
            normalizedJson = "{}";
        }
        if (extraJson == null || extraJson.isBlank()) {
            extraJson = "{}";
        }
    }
}
