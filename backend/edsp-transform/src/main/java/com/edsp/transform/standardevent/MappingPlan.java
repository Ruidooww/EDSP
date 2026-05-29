package com.edsp.transform.standardevent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MappingPlan(
    Map<String, String> fieldMappings,
    List<String> dedupFields,
    List<FieldMappingDetail> fieldMappingDetails
) {
    public record FieldMappingDetail(String sourceField, String standardField, String transformRule) {
    }

    public MappingPlan(Map<String, String> fieldMappings, List<String> dedupFields) {
        this(fieldMappings, dedupFields, List.of());
    }

    public MappingPlan {
        fieldMappings = fieldMappings == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(fieldMappings));
        dedupFields = dedupFields == null ? List.of() : List.copyOf(dedupFields);
        fieldMappingDetails = fieldMappingDetails == null ? List.of() : List.copyOf(fieldMappingDetails);
    }

    public static MappingPlan fromPlan(Map<String, Object> plan) {
        if (plan == null) {
            return new MappingPlan(Map.of(), List.of());
        }
        return new MappingPlan(fieldMappingSources(plan), dedupFields(plan), fieldMappingDetails(plan));
    }

    private static Map<String, String> fieldMappingSources(Map<String, Object> plan) {
        var mappings = new LinkedHashMap<String, String>();
        if (plan.get("fieldMappings") instanceof Map<?, ?> fields) {
            for (var entry : fields.entrySet()) {
                var sourceField = stringOrNull(entry.getKey());
                var standardField = stringOrNull(entry.getValue());
                if (sourceField != null && standardField != null) {
                    mappings.put(sourceField, standardField);
                }
            }
        }
        return mappings;
    }

    private static List<FieldMappingDetail> fieldMappingDetails(Map<String, Object> plan) {
        if (!(plan.get("fieldMappingDetails") instanceof List<?> details)) {
            return List.of();
        }
        var result = new ArrayList<FieldMappingDetail>();
        for (var item : details) {
            if (!(item instanceof Map<?, ?> mapping)) {
                continue;
            }
            var sourceField = stringOrNull(mapping.get("sourceField"));
            var standardField = stringOrNull(mapping.get("standardField"));
            if (sourceField != null && standardField != null) {
                result.add(new FieldMappingDetail(sourceField, standardField, rawStringOrNull(mapping.get("transformRule"))));
            }
        }
        return result;
    }

    private static List<String> dedupFields(Map<String, Object> plan) {
        if (!(plan.get("dedupStrategy") instanceof Map<?, ?> strategy)) {
            return List.of();
        }
        return stringList(strategy.get("fields"));
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (var item : list) {
                var text = stringOrNull(item);
                if (text != null) {
                    result.add(text);
                }
            }
            return result;
        }
        var item = stringOrNull(value);
        return item == null ? List.of() : List.of(item);
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String rawStringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
