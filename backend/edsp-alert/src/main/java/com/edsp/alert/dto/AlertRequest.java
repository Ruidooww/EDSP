package com.edsp.alert.dto;

import jakarta.validation.constraints.NotBlank;

public record AlertRequest(
    @NotBlank String title,
    @NotBlank String severity,
    String status,
    Long ruleId,
    String subjectType,
    String subjectRef,
    String detailJson
) {
    public AlertRequest {
        if (status == null || status.isBlank()) {
            status = "open";
        }
        if (detailJson == null || detailJson.isBlank()) {
            detailJson = "{}";
        }
    }
}
