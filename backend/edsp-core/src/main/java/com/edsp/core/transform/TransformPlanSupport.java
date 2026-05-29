package com.edsp.core.transform;

import com.edsp.core.support.CoreRequestSupport;
import com.edsp.transform.contract.TransformFieldMappingDto;
import com.edsp.transform.contract.TransformMappingPlanDto;
import com.edsp.transform.contract.TransformOptionsDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TransformPlanSupport {
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;

    public TransformPlanSupport(ObjectMapper objectMapper, CoreRequestSupport support) {
        this.objectMapper = objectMapper;
        this.support = support;
    }

    public Map<String, Object> parsePlan(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        try {
            var node = value instanceof byte[] bytes
                ? objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8))
                : objectMapper.readTree(String.valueOf(value));
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    public TransformMappingPlanDto mappingPlan(Map<String, Object> plan) {
        if (plan == null) {
            return new TransformMappingPlanDto(Map.of(), List.of());
        }
        return new TransformMappingPlanDto(fieldMappingSources(plan), dedupFields(plan), fieldMappingDetails(plan));
    }

    public TransformOptionsDto options(Long dataSourceId, Long schemaTableId, String sourceTable, String syncMode) {
        return new TransformOptionsDto(dataSourceId, schemaTableId, sourceTable, syncMode);
    }

    public List<String> selectedFields(Map<String, Object> plan) {
        var selected = new LinkedHashSet<String>();
        var mappingPlan = mappingPlan(plan);
        selected.addAll(mappingPlan.fieldMappings().keySet());
        selected.addAll(mappingPlan.dedupFields());
        var cursorField = support.stringOrNull(plan == null ? null : plan.get("cursorField"));
        if (cursorField != null) {
            selected.add(cursorField);
        }
        return new ArrayList<>(selected);
    }

    public Map<String, String> fieldMappingSources(Map<String, Object> plan) {
        var mappings = new LinkedHashMap<String, String>();
        if (plan == null) {
            return mappings;
        }
        if (plan.get("fieldMappings") instanceof Map<?, ?> fields) {
            for (var entry : fields.entrySet()) {
                var sourceField = support.stringOrNull(entry.getKey());
                var standardField = support.stringOrNull(entry.getValue());
                if (sourceField != null && standardField != null) {
                    mappings.put(sourceField, standardField);
                }
            }
        }
        if (mappings.isEmpty() && plan.get("fieldMappingDetails") instanceof List<?> details) {
            for (var item : details) {
                if (!(item instanceof Map<?, ?> mapping)) {
                    continue;
                }
                var sourceField = support.stringOrNull(mapping.get("sourceField"));
                var standardField = support.stringOrNull(mapping.get("standardField"));
                if (sourceField != null && standardField != null) {
                    mappings.put(sourceField, standardField);
                }
            }
        }
        return mappings;
    }

    public List<String> dedupFields(Map<String, Object> plan) {
        if (plan == null || !(plan.get("dedupStrategy") instanceof Map<?, ?> strategy)) {
            return List.of();
        }
        return stringList(strategy.get("fields"));
    }

    private List<TransformFieldMappingDto> fieldMappingDetails(Map<String, Object> plan) {
        if (plan == null || !(plan.get("fieldMappingDetails") instanceof List<?> details)) {
            return List.of();
        }
        var result = new ArrayList<TransformFieldMappingDto>();
        for (var item : details) {
            if (!(item instanceof Map<?, ?> mapping)) {
                continue;
            }
            var sourceField = support.stringOrNull(mapping.get("sourceField"));
            var standardField = support.stringOrNull(mapping.get("standardField"));
            if (sourceField != null && standardField != null) {
                result.add(new TransformFieldMappingDto(
                    sourceField,
                    standardField,
                    rawStringOrNull(mapping.get("transformRule")),
                    objectMap(mapping.get("transformRulePayload"))
                ));
            }
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (var item : list) {
                var text = support.stringOrNull(item);
                if (text != null) {
                    result.add(text);
                }
            }
            return result;
        }
        var item = support.stringOrNull(value);
        return item == null ? List.of() : List.of(item);
    }

    private String rawStringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
