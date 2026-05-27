package com.edsp.transform.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BatchTransformRequest(
    List<Map<String, Object>> rows,
    TransformMappingPlanDto mappingPlan,
    TransformOptionsDto options
) {
    public BatchTransformRequest {
        rows = rows == null
            ? null
            : rows.stream()
                .map(row -> row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row)))
                .toList();
    }
}
