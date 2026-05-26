package com.edsp.transform.standardevent;

public record TransformOptions(Long dataSourceId, Long schemaTableId, String sourceTable, String syncMode) {
}
