package com.edsp.core.dto;

import jakarta.validation.constraints.NotBlank;

public record DataSourceRequest(
    @NotBlank String name,
    @NotBlank String sourceType,
    @NotBlank String connectionKind,
    String description,
    String configJson,
    boolean enabled
) {
    public DataSourceRequest {
        if (configJson == null || configJson.isBlank()) {
            configJson = "{}";
        }
    }
}
