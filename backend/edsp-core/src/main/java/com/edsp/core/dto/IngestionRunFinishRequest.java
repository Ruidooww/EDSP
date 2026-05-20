package com.edsp.core.dto;

public record IngestionRunFinishRequest(
    String status,
    String cursorAfter,
    Long readCount,
    Long successCount,
    Long failedCount,
    Long skippedCount,
    String errorMessage,
    String qualityReportJson
) {
    public IngestionRunFinishRequest {
        if (status == null || status.isBlank()) {
            status = "success";
        }
        if (readCount == null) {
            readCount = 0L;
        }
        if (successCount == null) {
            successCount = 0L;
        }
        if (failedCount == null) {
            failedCount = 0L;
        }
        if (skippedCount == null) {
            skippedCount = 0L;
        }
        if (qualityReportJson == null || qualityReportJson.isBlank()) {
            qualityReportJson = "{}";
        }
    }
}
