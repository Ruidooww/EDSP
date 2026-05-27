package com.edsp.transform.contract;

import java.util.List;

public record TransformResponse(
    TransformDraftDto draft,
    List<String> errors,
    List<String> warnings
) {
    public TransformResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
