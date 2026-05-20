package com.edsp.alert.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record IngestAlertRequest(
    @NotBlank String sourceSystem,
    String externalId,
    String alertType,
    @NotBlank String title,
    @NotBlank String severity,
    String occurredAt,
    String actor,
    String asset,
    String policyName,
    String subjectType,
    String subjectRef,
    String status,
    Map<String, Object> detail
) {
    public IngestAlertRequest {
        if (status == null || status.isBlank()) {
            status = "open";
        }
        if (subjectType == null || subjectType.isBlank()) {
            subjectType = "external_alert";
        }
        if (subjectRef == null || subjectRef.isBlank()) {
            subjectRef = externalId;
        }
        if (alertType == null || alertType.isBlank()) {
            alertType = "generic";
        }
    }
}
