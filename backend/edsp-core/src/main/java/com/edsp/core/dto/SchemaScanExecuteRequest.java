package com.edsp.core.dto;

public record SchemaScanExecuteRequest(
    long dataSourceId,
    String database,
    Integer tableLimit,
    Integer fieldLimit,
    Boolean includeViews
) {
    public SchemaScanExecuteRequest {
        if (database == null) {
            database = "";
        }
        if (tableLimit == null) {
            tableLimit = 200;
        }
        if (fieldLimit == null) {
            fieldLimit = 300;
        }
        if (includeViews == null) {
            includeViews = false;
        }
    }
}
