package com.edsp.core.dto;

public record IngestionPlanGenerateRequest(
    Long dataSourceId,
    Long scanRunId
) {
}
