package com.edsp.core.dto;

public record RuleRequest(
    String name,
    String eventType,
    String severity,
    String expression,
    Boolean enabled
) {
}
