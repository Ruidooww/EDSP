package com.edsp.core.dto;

public record IngestionPlanSyncOnceRequest(
    Integer sampleLimit,
    String operatorName
) {
}
