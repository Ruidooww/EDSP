package com.edsp.transform.contract;

import java.util.List;

public record TransformResultItem(
    int index,
    TransformDraftDto draft,
    List<String> errors,
    List<String> warnings
) {
    public TransformResultItem {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
