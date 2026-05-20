package com.edsp.report.dto;

import jakarta.validation.constraints.NotBlank;

public record ReportJobRequest(
    @NotBlank String reportType,
    @NotBlank String title,
    String paramsJson
) {
    public ReportJobRequest {
        if (paramsJson == null || paramsJson.isBlank()) {
            paramsJson = "{}";
        }
    }
}
