package com.edsp.core.service;

import com.edsp.core.dto.IngestionPlanShadowValidationRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionPlanPrecheckService {
    private static final String INGESTION_MODE = "database_polling";
    private static final int DEFAULT_SHADOW_SAMPLE_LIMIT = 50;
    private static final int MAX_SHADOW_SAMPLE_LIMIT = 100;
    private static final Set<String> SHADOW_VALIDATION_STATUSES = Set.of("approved", "shadow_ready");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;

    public IngestionPlanPrecheckService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
    }

    // Shadow Precheck only: no writes to standard_events/alert_decisions/alerts and no notifications.
    public Map<String, Object> shadowValidate(long id, IngestionPlanShadowValidationRequest request) {
        var rows = jdbcTemplate.queryForList("""
            select ip.id, ip.data_source_id, ds.name as data_source_name, ip.scan_run_id,
                   ip.name, ip.status, ip.plan_json, ip.created_at, ip.updated_at
            from ingestion_plans ip
            join data_sources ds on ds.id = ip.data_source_id
            where ip.id = ?
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + id);
        }

        var row = rows.get(0);
        var status = support.stringOrDefault(row.get("status"), "");
        if (!SHADOW_VALIDATION_STATUSES.contains(status)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Ingestion plan must be approved or shadow_ready before Shadow Precheck: " + status
            );
        }

        var plan = parsePlan(row.get("plan_json"));
        var checks = new ArrayList<Map<String, Object>>();
        var blockers = new LinkedHashSet<String>();
        var warnings = new LinkedHashSet<String>();
        var dataSourceId = support.number(row.get("data_source_id"));
        var schemaTableId = longValue(plan.get("schemaTableId"));
        var sourceFields = schemaTableId == null ? Map.<String, Object>of() : loadActiveFieldSamples(schemaTableId);
        var fieldMappings = fieldMappingSources(plan);

        addCheck(checks, "plan_status", "passed", "Plan is approved for Shadow Precheck.");
        if (INGESTION_MODE.equals(support.stringOrDefault(plan.get("mode"), ""))) {
            addCheck(checks, "plan_mode", "passed", "Plan uses database polling mode.");
        } else {
            blockers.add("unsupported_plan_mode");
            addCheck(checks, "plan_mode", "failed", "Only database_polling plans can run Shadow Precheck.", "unsupported_plan_mode");
        }

        validateSourceTable(dataSourceId, schemaTableId, checks, blockers);
        validateSourceFields(fieldMappings, sourceFields, checks, blockers);
        validateRequiredFields(plan, checks, blockers);
        validateDedupStrategy(plan, sourceFields.keySet(), checks, blockers);
        validateSyncGuard(plan, checks, blockers);

        var planRisks = stringList(plan.get("risks"));
        if (planRisks.isEmpty()) {
            addCheck(checks, "plan_risks", "passed", "Plan does not report extra risk flags.");
        } else {
            warnings.addAll(planRisks);
            addCheck(checks, "plan_risks", "warning", "Plan carries risk flags.", (List<String>) null, planRisks);
        }

        var result = blockers.isEmpty() ? (warnings.isEmpty() ? "passed" : "warning") : "blocked";
        var report = new LinkedHashMap<String, Object>();
        report.put("planId", id);
        report.put("dataSourceId", dataSourceId);
        report.put("dataSourceName", support.stringOrNull(row.get("data_source_name")));
        report.put("planStatus", status);
        report.put("result", result);
        report.put("statusRecommendation", blockers.isEmpty() ? "shadow_ready" : "manual_review");
        report.put("checkedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        report.put("sampleLimit", shadowSampleLimit(request));
        report.put("mainTable", support.stringOrNull(plan.get("mainTable")));
        report.put("templateKey", support.stringOrNull(templateKey(plan)));
        report.put("mappedFieldCount", fieldMappings.size());
        report.put("blockers", new ArrayList<>(blockers));
        report.put("warnings", new ArrayList<>(warnings));
        report.put("standardEventPreview", standardEventPreview(fieldMappings, sourceFields));
        report.put("checks", checks);
        return report;
    }

    private int shadowSampleLimit(IngestionPlanShadowValidationRequest request) {
        var requested = request == null || request.sampleLimit() == null
            ? DEFAULT_SHADOW_SAMPLE_LIMIT
            : request.sampleLimit();
        return support.safeLimit(requested, MAX_SHADOW_SAMPLE_LIMIT);
    }

    private Map<String, Object> loadActiveFieldSamples(Long schemaTableId) {
        var rows = jdbcTemplate.queryForList("""
            select field_name, sample_value
            from schema_fields
            where schema_table_id = ?
              and coalesce(lifecycle_status, 'active') = 'active'
            order by ordinal_position nulls last, id
            """, schemaTableId);
        var fields = new LinkedHashMap<String, Object>();
        for (var row : rows) {
            var fieldName = support.stringOrNull(row.get("field_name"));
            if (fieldName != null) {
                fields.put(fieldName, row.get("sample_value"));
            }
        }
        return fields;
    }

    private void validateSourceTable(
        Long dataSourceId,
        Long schemaTableId,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (dataSourceId == null || schemaTableId == null) {
            blockers.add("source_table_missing");
            addCheck(checks, "source_table", "failed", "Plan does not reference a source table.", "source_table_missing");
            return;
        }
        var rows = jdbcTemplate.queryForList("""
            select id
            from schema_tables
            where id = ?
              and data_source_id = ?
              and coalesce(lifecycle_status, 'active') = 'active'
            """, schemaTableId, dataSourceId);
        if (rows.isEmpty()) {
            blockers.add("source_table_missing");
            addCheck(checks, "source_table", "failed", "Plan source table is not active or does not belong to this data source.",
                "source_table_missing");
            return;
        }
        addCheck(checks, "source_table", "passed", "Plan source table is active.");
    }

    private void validateSourceFields(
        Map<String, String> fieldMappings,
        Map<String, Object> sourceFields,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (fieldMappings.isEmpty()) {
            blockers.add("field_mappings_missing");
            addCheck(checks, "source_fields", "failed", "Plan does not include field mappings.", "field_mappings_missing");
            return;
        }
        var missing = fieldMappings.keySet().stream()
            .filter(field -> !sourceFields.containsKey(field))
            .toList();
        if (!missing.isEmpty()) {
            blockers.add("mapped_source_fields_missing");
            addCheck(checks, "source_fields", "failed", "Some mapped source fields are no longer active.",
                "mapped_source_fields_missing", missing);
            return;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("mappedFieldCount", fieldMappings.size());
        addCheck(checks, "source_fields", "passed", "All mapped source fields are active.", (List<String>) null, details);
    }

    private void validateRequiredFields(
        Map<String, Object> plan,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        var requiredMissing = stringList(plan.get("requiredFieldsMissing"));
        var checkBlockers = new ArrayList<String>();
        if (requiredMissing.contains("occurred_at") || support.stringOrNull(plan.get("cursorField")) == null) {
            checkBlockers.add("missing_occurred_at");
        }
        if (requiredMissing.contains("dedup_key_source_insufficient")) {
            checkBlockers.add("dedup_key_source_insufficient");
        }
        if (!checkBlockers.isEmpty()) {
            blockers.addAll(checkBlockers);
            addCheck(checks, "required_fields", "failed", "Plan is missing required fields for Shadow Precheck.",
                checkBlockers, null);
            return;
        }
        addCheck(checks, "required_fields", "passed", "Required event time and dedup inputs are present.");
    }

    private void validateDedupStrategy(
        Map<String, Object> plan,
        Set<String> sourceFields,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (!(plan.get("dedupStrategy") instanceof Map<?, ?> strategy)) {
            blockers.add("dedup_strategy_missing");
            addCheck(checks, "dedup_strategy", "failed", "Plan does not include a dedup strategy.", "dedup_strategy_missing");
            return;
        }
        var type = support.stringOrNull(strategy.get("type"));
        var fields = stringList(strategy.get("fields"));
        var missing = fields.stream().filter(field -> !sourceFields.contains(field)).toList();
        if (fields.isEmpty()) {
            blockers.add("dedup_strategy_missing");
            addCheck(checks, "dedup_strategy", "failed", "Dedup strategy has no source fields.", "dedup_strategy_missing");
            return;
        }
        if (!missing.isEmpty()) {
            blockers.add("dedup_fields_missing");
            addCheck(checks, "dedup_strategy", "failed", "Dedup strategy references inactive source fields.",
                "dedup_fields_missing", missing);
            return;
        }
        if ("composite".equals(type) && !boolValue(strategy.get("stable"))) {
            blockers.add("dedup_key_source_insufficient");
            addCheck(checks, "dedup_strategy", "failed", "Composite dedup strategy is not stable.",
                "dedup_key_source_insufficient");
            return;
        }
        addCheck(checks, "dedup_strategy", "passed", "Dedup strategy can be evaluated during Shadow Precheck.");
    }

    private void validateSyncGuard(
        Map<String, Object> plan,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (!(plan.get("syncStrategy") instanceof Map<?, ?> strategy)) {
            blockers.add("sync_guard_missing");
            addCheck(checks, "sync_guard", "failed", "Plan does not include a sync strategy.", "sync_guard_missing");
            return;
        }
        var shadowOnly = boolValue(strategy.get("shadowOnly"));
        var enabled = boolValue(strategy.get("enabled"));
        if (!shadowOnly || enabled) {
            blockers.add("sync_guard_unsafe");
            addCheck(checks, "sync_guard", "failed", "Shadow Precheck requires shadowOnly=true and enabled=false.",
                "sync_guard_unsafe");
            return;
        }
        addCheck(checks, "sync_guard", "passed", "Sync strategy is guarded as shadow-only.");
    }

    private Map<String, String> fieldMappingSources(Map<String, Object> plan) {
        var mappings = new LinkedHashMap<String, String>();
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

    private Map<String, Object> standardEventPreview(Map<String, String> fieldMappings, Map<String, Object> sourceFields) {
        var preview = new LinkedHashMap<String, Object>();
        for (var entry : fieldMappings.entrySet()) {
            if (sourceFields.containsKey(entry.getKey())) {
                preview.put(entry.getValue(), sourceFields.get(entry.getKey()));
            }
        }
        return preview;
    }

    private void addCheck(List<Map<String, Object>> checks, String code, String result, String message) {
        addCheck(checks, code, result, message, (List<String>) null, null);
    }

    private void addCheck(List<Map<String, Object>> checks, String code, String result, String message, String blocker) {
        addCheck(checks, code, result, message, blocker == null ? null : List.of(blocker), null);
    }

    private void addCheck(
        List<Map<String, Object>> checks,
        String code,
        String result,
        String message,
        String blocker,
        Object details
    ) {
        addCheck(checks, code, result, message, blocker == null ? null : List.of(blocker), details);
    }

    private void addCheck(
        List<Map<String, Object>> checks,
        String code,
        String result,
        String message,
        List<String> blockers,
        Object details
    ) {
        var check = new LinkedHashMap<String, Object>();
        check.put("code", code);
        check.put("result", result);
        check.put("message", message);
        if (blockers != null && !blockers.isEmpty()) {
            check.put("blockers", blockers);
        }
        if (details != null) {
            check.put("details", details);
        }
        checks.add(check);
    }

    private Map<String, Object> parsePlan(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        try {
            var node = value instanceof byte[] bytes
                ? objectMapper.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                : objectMapper.readTree(String.valueOf(value));
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private Object templateKey(Map<String, Object> plan) {
        if (plan.get("templateMatch") instanceof Map<?, ?> template) {
            return template.get("templateKey");
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        var text = support.stringOrNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(support::stringOrNull)
                .filter(item -> item != null)
                .toList();
        }
        var item = support.stringOrNull(value);
        return item == null ? List.of() : List.of(item);
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(support.stringOrDefault(value, "false"));
    }
}
