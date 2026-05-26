package com.edsp.transform.standardevent;

import java.util.List;

public record TransformResult(StandardEventDraft draft, List<String> errors, List<String> warnings) {
    public TransformResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
