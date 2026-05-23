package com.edsp.core.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record IngestionPlanShadowRunRequest(
    @Min(1) @Max(100) Integer sampleLimit
) {
}
