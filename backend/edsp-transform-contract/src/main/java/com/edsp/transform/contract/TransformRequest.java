package com.edsp.transform.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TransformRequest(
    Map<String, Object> row,
    TransformMappingPlanDto mappingPlan,
    TransformOptionsDto options
) {
    public TransformRequest {
        row = row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
}
