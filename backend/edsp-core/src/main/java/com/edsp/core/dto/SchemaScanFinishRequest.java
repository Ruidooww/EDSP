package com.edsp.core.dto;

public record SchemaScanFinishRequest(
    String status,
    Integer totalDatabases,
    Integer scannedDatabases,
    Integer failedDatabases,
    Integer totalTables,
    Integer scannedTables,
    Integer failedTables,
    Integer totalFields,
    Integer scannedFields,
    Boolean limited,
    Double coverageRate,
    String errorMessage,
    String resultJson
) {
    public SchemaScanFinishRequest {
        if (status == null || status.isBlank()) {
            status = "success";
        }
        if (totalDatabases == null) totalDatabases = 0;
        if (scannedDatabases == null) scannedDatabases = 0;
        if (failedDatabases == null) failedDatabases = 0;
        if (totalTables == null) totalTables = 0;
        if (scannedTables == null) scannedTables = 0;
        if (failedTables == null) failedTables = 0;
        if (totalFields == null) totalFields = 0;
        if (scannedFields == null) scannedFields = 0;
        if (limited == null) limited = scannedTables < totalTables;
        if (coverageRate == null) {
            coverageRate = totalTables <= 0 ? 100.0d : scannedTables * 100.0d / totalTables;
        }
        if (resultJson == null || resultJson.isBlank()) {
            resultJson = "{}";
        }
    }
}
