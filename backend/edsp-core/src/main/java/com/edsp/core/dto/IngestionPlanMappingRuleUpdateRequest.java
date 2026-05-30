package com.edsp.core.dto;

import java.util.Map;

public record IngestionPlanMappingRuleUpdateRequest(
    String sourceField,
    String standardField,
    String transformRule,
    Map<String, Object> transformRulePayload
) {
}
