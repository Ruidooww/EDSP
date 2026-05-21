package com.edsp.core.dto;

import java.util.List;

public record SchemaChangeActionRequest(
    String action,
    String operator,
    String comment,
    List<Long> ids
) {
    public SchemaChangeActionRequest {
        action = action == null ? "" : action.trim();
        operator = operator == null || operator.isBlank() ? "admin" : operator.trim();
        comment = comment == null ? "" : comment.trim();
        ids = ids == null ? List.of() : ids;
    }
}
