package com.edsp.transform.standardevent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SourceRow(Map<String, Object> values) {
    public SourceRow {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
