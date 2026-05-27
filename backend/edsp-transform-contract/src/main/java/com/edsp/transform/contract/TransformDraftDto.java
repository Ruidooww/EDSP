package com.edsp.transform.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TransformDraftDto(
    String sourceSystem,
    String externalId,
    String eventType,
    String occurredAt,
    String actor,
    String assetRef,
    String subjectType,
    String subjectRef,
    String action,
    String result,
    String severity,
    Integer riskScore,
    String dedupKey,
    Map<String, Object> normalized,
    Map<String, Object> extra
) {
    public TransformDraftDto {
        normalized = normalized == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
        extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }
}
