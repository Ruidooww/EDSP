package com.edsp.transform.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TransformMappingPlanDto(
    Map<String, String> fieldMappings,
    List<String> dedupFields,
    List<TransformFieldMappingDto> fieldMappingDetails
) {
    public TransformMappingPlanDto(Map<String, String> fieldMappings, List<String> dedupFields) {
        this(fieldMappings, dedupFields, List.of());
    }

    public TransformMappingPlanDto {
        fieldMappings = fieldMappings == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(fieldMappings));
        dedupFields = dedupFields == null ? List.of() : List.copyOf(dedupFields);
        fieldMappingDetails = fieldMappingDetails == null ? List.of() : List.copyOf(fieldMappingDetails);
    }
}
