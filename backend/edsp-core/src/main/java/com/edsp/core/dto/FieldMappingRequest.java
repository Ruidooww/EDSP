package com.edsp.core.dto;

import jakarta.validation.constraints.NotBlank;

public record FieldMappingRequest(
    long schemaTableId,
    @NotBlank String sourceField,
    @NotBlank String standardField,
    String transformRule
) {
}
