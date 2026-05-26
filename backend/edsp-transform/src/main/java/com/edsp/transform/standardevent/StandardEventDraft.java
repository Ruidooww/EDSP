package com.edsp.transform.standardevent;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record StandardEventDraft(
    String sourceSystem,
    String externalId,
    String eventType,
    OffsetDateTime occurredAt,
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
    public StandardEventDraft {
        normalized = normalized == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
        extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }
}
