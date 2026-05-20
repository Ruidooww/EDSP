package com.edsp.alert.dto;

import jakarta.validation.constraints.NotBlank;

public record AlertNoteRequest(
    String operatorName,
    @NotBlank String note,
    String status
) {
    public AlertNoteRequest {
        if (operatorName == null || operatorName.isBlank()) {
            operatorName = "admin";
        }
        if (status != null && status.isBlank()) {
            status = null;
        }
    }
}
