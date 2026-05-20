package com.edsp.core.dto;

public record SchemaScanRunRequest(
    long dataSourceId,
    String scanType,
    String status,
    String resultJson
) {
    public SchemaScanRunRequest {
        if (scanType == null || scanType.isBlank()) {
            scanType = "metadata";
        }
        if (status == null || status.isBlank()) {
            status = "running";
        }
        if (resultJson == null || resultJson.isBlank()) {
            resultJson = "{}";
        }
    }
}
