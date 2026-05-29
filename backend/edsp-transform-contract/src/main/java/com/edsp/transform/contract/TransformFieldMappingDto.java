package com.edsp.transform.contract;

public record TransformFieldMappingDto(
    String sourceField,
    String standardField,
    String transformRule
) {
}
