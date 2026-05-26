package com.edsp.transform.contract;

import java.util.List;

public record BatchTransformResponse(
    List<TransformResultItem> results,
    List<String> errors,
    List<String> warnings
) {
    public BatchTransformResponse {
        results = results == null ? List.of() : List.copyOf(results);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
