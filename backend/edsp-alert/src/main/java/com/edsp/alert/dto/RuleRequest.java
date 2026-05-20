package com.edsp.alert.dto;

import jakarta.validation.constraints.NotBlank;

public record RuleRequest(
    @NotBlank String name,
    @NotBlank String eventType,
    @NotBlank String severity,
    @NotBlank String expression,
    boolean enabled
) {
}
