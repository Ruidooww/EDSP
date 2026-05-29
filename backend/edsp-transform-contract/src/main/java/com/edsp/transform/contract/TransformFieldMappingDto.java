package com.edsp.transform.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TransformFieldMappingDto(
    String sourceField,
    String standardField,
    String transformRule,
    Map<String, Object> transformRulePayload
) {
    public TransformFieldMappingDto(String sourceField, String standardField, String transformRule) {
        this(sourceField, standardField, transformRule, Map.of());
    }

    public TransformFieldMappingDto {
        transformRulePayload = immutablePayload(transformRulePayload);
    }

    private static Map<String, Object> immutablePayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
