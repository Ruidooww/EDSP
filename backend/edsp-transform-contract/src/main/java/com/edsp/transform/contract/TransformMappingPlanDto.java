package com.edsp.transform.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public record TransformMappingPlanDto(
    Map<String, String> fieldMappings,
    List<String> dedupFields
) {
    public TransformMappingPlanDto {
        fieldMappings = fieldMappings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fieldMappings));
        dedupFields = dedupFields == null ? List.of() : List.copyOf(dedupFields);
    }
}
