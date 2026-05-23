package com.edsp.core.dto;

public record IngestionPlanSyncScheduleRequest(
    Integer intervalSeconds,
    Integer sampleLimit,
    String operatorName
) {
}
