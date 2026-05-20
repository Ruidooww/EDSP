package com.edsp.core.dto;

import jakarta.validation.constraints.NotBlank;

public record SchemaFieldRequest(
    @NotBlank String fieldName,
    @NotBlank String fieldType,
    boolean nullable,
    String sampleValue,
    String description
) {
}
