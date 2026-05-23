package com.edsp.core.dto;

public record IngestionPlanActivationRequest(
    Long shadowRunId,
    String operatorName,
    String reason
) {
}
