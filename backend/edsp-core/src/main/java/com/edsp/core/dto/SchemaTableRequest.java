package com.edsp.core.dto;

import jakarta.validation.constraints.NotBlank;

public record SchemaTableRequest(
    long dataSourceId,
    @NotBlank String tableName,
    String category,
    String confirmationStatus
) {
    public SchemaTableRequest {
        if (confirmationStatus == null || confirmationStatus.isBlank()) {
            confirmationStatus = "pending";
        }
    }
}
