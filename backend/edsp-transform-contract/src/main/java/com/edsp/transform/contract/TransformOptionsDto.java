package com.edsp.transform.contract;

public record TransformOptionsDto(
    Long dataSourceId,
    Long schemaTableId,
    String sourceTable,
    String syncMode
) {
}
