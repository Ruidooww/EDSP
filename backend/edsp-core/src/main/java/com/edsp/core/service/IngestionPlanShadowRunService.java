package com.edsp.core.service;

import com.edsp.core.dto.IngestionPlanShadowRunRequest;
import com.edsp.core.dto.IngestionPlanShadowValidationRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionPlanShadowRunService {
    private static final int DEFAULT_SAMPLE_LIMIT = 50;
    private static final int MAX_SAMPLE_LIMIT = 100;
    private static final int MAX_PREVIEW_SAMPLES = 20;
    private static final int MAX_VALUE_LENGTH = 256;
    private static final Set<String> RUN_ALLOWED_PLAN_STATUSES = Set.of("approved", "shadow_ready");
    private static final List<String> EXCLUDED_SEMANTICS = List.of("detail");
    private static final List<String> EXCLUDED_FIELD_PATTERNS = List.of("payload", "raw", "content", "body");
    private static final Map<String, String> NORMALIZED_SEVERITIES = Map.ofEntries(
        Map.entry("critical", "critical"),
        Map.entry("严重", "critical"),
        Map.entry("1", "critical"),
        Map.entry("high", "high"),
        Map.entry("高", "high"),
        Map.entry("2", "high"),
        Map.entry("medium", "medium"),
        Map.entry("中", "medium"),
        Map.entry("一般", "medium"),
        Map.entry("warning", "medium"),
        Map.entry("3", "medium"),
        Map.entry("low", "low"),
        Map.entry("低", "low"),
        Map.entry("4", "low"),
        Map.entry("info", "info"),
        Map.entry("提示", "info")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;
    private final IngestionPlanPrecheckService precheckService;
    private final JdbcShadowSampleService sampleService;

    public IngestionPlanShadowRunService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support,
        IngestionPlanPrecheckService precheckService,
        JdbcShadowSampleService sampleService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
        this.precheckService = precheckService;
        this.sampleService = sampleService;
    }

    public Map<String, Object> createShadowRun(long planId, IngestionPlanShadowRunRequest request) {
        var startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        var sampleLimit = sampleLimit(request);
        var planRow = loadPlanRow(planId);
        var planStatus = support.stringOrDefault(planRow.get("status"), "");
        if (!RUN_ALLOWED_PLAN_STATUSES.contains(planStatus)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Ingestion plan must be approved or shadow_ready before Shadow Run: " + planStatus
            );
        }

        var dataSourceId = support.number(planRow.get("data_source_id"));
        var precheck = precheckService.shadowValidate(planId, new IngestionPlanShadowValidationRequest(sampleLimit));
        if ("blocked".equals(support.stringOrDefault(precheck.get("result"), ""))) {
            var report = reportSkeleton(planId, dataSourceId, sampleLimit, precheck);
            var runId = insertRun(planId, dataSourceId, "blocked", sampleLimit, 0, 0, 0, 0, 0,
                startedAt, null, report);
            return runRow(runId, true);
        }

        try {
            var plan = parsePlan(planRow.get("plan_json"));
            var source = loadSource(planRow, plan);
            var rows = sampleService.sample(
                source.sourceType(),
                source.configJson(),
                source.schemaName(),
                source.tableName(),
                source.selectedFields(),
                sampleLimit
            );
            var analysis = analyze(planId, dataSourceId, sampleLimit, precheck, source, rows);
            var status = analysis.status();
            var runId = insertRun(
                planId,
                dataSourceId,
                status,
                sampleLimit,
                rows.size(),
                analysis.successCount(),
                analysis.failedCount(),
                analysis.duplicateCount(),
                analysis.missingRequiredCount(),
                startedAt,
                null,
                analysis.report()
            );
            return runRow(runId, true);
        } catch (PlanBlockerException ex) {
            var error = support.stringOrDefault(ex.getMessage(), "Shadow run blocked");
            var report = reportSkeleton(planId, dataSourceId, sampleLimit, precheck);
            var blockers = new LinkedHashSet<>(stringList(report.get("blockers")));
            blockers.addAll(ex.blockers());
            var checks = new ArrayList<Object>();
            if (report.get("checks") instanceof List<?> existingChecks) {
                checks.addAll(existingChecks);
            }
            checks.add(failedCheck(ex, error));
            report.put("status", "blocked");
            report.put("summary", summary("blocked", sampleLimit, 0, 0, 0, 0, 0));
            report.put("checks", checks);
            report.put("blockers", new ArrayList<>(blockers));
            report.put("errorsByType", Map.of(ex.errorType(), 1));
            report.put("errorMessage", error);
            var runId = insertRun(planId, dataSourceId, "blocked", sampleLimit, 0, 0, 0, 0, 0,
                startedAt, error, report);
            return runRow(runId, true);
        } catch (RuntimeException ex) {
            var error = support.stringOrDefault(ex.getMessage(), "Shadow run failed");
            var report = reportSkeleton(planId, dataSourceId, sampleLimit, precheck);
            report.put("status", "failed");
            report.put("summary", summary("failed", sampleLimit, 0, 0, 0, 0, 0));
            report.put("errorsByType", Map.of("execution_failed", 1));
            report.put("errorMessage", error);
            var runId = insertRun(planId, dataSourceId, "failed", sampleLimit, 0, 0, 0, 0, 0,
                startedAt, error, report);
            return runRow(runId, true);
        }
    }

    public List<Map<String, Object>> listShadowRuns(long planId, int limit) {
        ensurePlanExists(planId);
        return jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, status, sample_limit, read_count,
                   success_count, failed_count, duplicate_count, missing_required_count,
                   started_at, finished_at, duration_ms, error_message, created_at, updated_at
            from ingestion_plan_shadow_runs
            where ingestion_plan_id = ?
            order by created_at desc, id desc
            limit ?
            """, planId, support.safeLimit(limit, 50)).stream()
            .map(row -> runRow(row, false))
            .toList();
    }

    public Map<String, Object> shadowRunDetail(long runId) {
        var rows = jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, status, sample_limit, read_count,
                   success_count, failed_count, duplicate_count, missing_required_count,
                   started_at, finished_at, duration_ms, error_message, report_json, created_at, updated_at
            from ingestion_plan_shadow_runs
            where id = ?
            """, runId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan shadow run not found: " + runId);
        }
        return runRow(rows.get(0), true);
    }

    private Analysis analyze(
        long planId,
        Long dataSourceId,
        int sampleLimit,
        Map<String, Object> precheck,
        Source source,
        List<Map<String, Object>> rows
    ) {
        var fieldMappings = fieldMappingSources(source.plan());
        var dedupFields = dedupFields(source.plan());
        var occurredAtField = sourceFieldForStandardField(fieldMappings, "occurredAt");
        var severityField = sourceFieldForStandardField(fieldMappings, "severity");
        var seenDedupKeys = new LinkedHashSet<String>();
        var samples = new ArrayList<Map<String, Object>>();
        var errorsByType = new LinkedHashMap<String, Integer>();
        var warnings = new LinkedHashSet<String>();
        var successCount = 0;
        var failedCount = 0;
        var duplicateCount = 0;
        var missingRequiredCount = 0;

        for (var sourceRow : rows) {
            var errors = new ArrayList<String>();
            var sampleWarnings = new ArrayList<String>();
            OffsetDateTime parsedOccurredAt = null;
            String normalizedSeverity = null;
            var missingRequired = false;
            if (occurredAtField == null || blank(sourceRow.get(occurredAtField))) {
                errors.add("missing_occurred_at");
                increment(errorsByType, "missing_occurred_at");
                missingRequired = true;
            } else {
                try {
                    parsedOccurredAt = support.parseTime(String.valueOf(sourceRow.get(occurredAtField)));
                    if (parsedOccurredAt == null) {
                        errors.add("missing_occurred_at");
                        increment(errorsByType, "missing_occurred_at");
                        missingRequired = true;
                    }
                } catch (RuntimeException ex) {
                    errors.add("invalid_time_format");
                    increment(errorsByType, "invalid_time_format");
                }
            }
            if (severityField != null) {
                normalizedSeverity = normalizeSeverity(sourceRow.get(severityField));
                if (normalizedSeverity == null) {
                    errors.add("severity_unrecognized");
                    increment(errorsByType, "severity_unrecognized");
                }
            }
            var dedupKey = dedupKey(sourceRow, dedupFields);
            if (dedupKey == null) {
                errors.add("dedup_key_missing");
                increment(errorsByType, "dedup_key_missing");
                missingRequired = true;
            } else if (!seenDedupKeys.add(dedupKey)) {
                duplicateCount++;
                sampleWarnings.add("duplicate_in_sample");
                warnings.add("duplicate_in_sample");
            }
            if (errors.isEmpty()) {
                successCount++;
            } else {
                failedCount++;
                if (missingRequired) {
                    missingRequiredCount++;
                }
            }
            samples.add(samplePreview(sourceRow, source.selectedFields(), fieldMappings,
                source.fieldMetadata(), dedupKey == null ? null : sha256(dedupKey), parsedOccurredAt,
                normalizedSeverity, errors, sampleWarnings));
        }

        if (rows.isEmpty()) {
            warnings.add("no_sample_rows");
        }
        var precheckResult = support.stringOrDefault(precheck.get("result"), "passed");
        var status = failedCount > 0 || duplicateCount > 0 || !warnings.isEmpty() || "warning".equals(precheckResult)
            ? "warning"
            : "passed";
        var mergedWarnings = new LinkedHashSet<>(stringList(precheck.get("warnings")));
        mergedWarnings.addAll(warnings);
        var report = reportSkeleton(planId, dataSourceId, sampleLimit, precheck);
        report.put("status", status);
        report.put("summary", summary(status, sampleLimit, rows.size(), successCount, failedCount,
            duplicateCount, missingRequiredCount));
        report.put("samples", samples.stream()
            .sorted(Comparator.comparing(sample -> ((List<?>) sample.get("errors")).isEmpty()))
            .limit(MAX_PREVIEW_SAMPLES)
            .toList());
        report.put("errorsByType", errorsByType);
        report.put("warnings", new ArrayList<>(mergedWarnings));
        return new Analysis(status, successCount, failedCount, duplicateCount, missingRequiredCount, report);
    }

    private Map<String, Object> samplePreview(
        Map<String, Object> sourceRow,
        List<String> selectedFields,
        Map<String, String> fieldMappings,
        Map<String, FieldMetadata> metadata,
        String dedupKeyPreview,
        OffsetDateTime parsedOccurredAt,
        String normalizedSeverity,
        List<String> errors,
        List<String> warnings
    ) {
        var sourcePreview = new LinkedHashMap<String, Object>();
        var standardPreview = new LinkedHashMap<String, Object>();
        for (var sourceField : selectedFields) {
            var value = sourceRow.get(sourceField);
            var fieldMetadata = metadata.getOrDefault(sourceField, new FieldMetadata(sourceField, ""));
            sourcePreview.put(sourceField, previewValue(sourceField, fieldMetadata.semanticType(), value));
        }
        for (var entry : fieldMappings.entrySet()) {
            var standardField = entry.getValue();
            if ("occurredAt".equals(standardField) || "severity".equals(standardField)) {
                continue;
            }
            var value = sourceRow.get(entry.getKey());
            var fieldMetadata = metadata.getOrDefault(entry.getKey(), new FieldMetadata(entry.getKey(), ""));
            standardPreview.put(standardField, standardPreviewValue(standardField, entry.getKey(),
                fieldMetadata.semanticType(), value));
        }
        if (parsedOccurredAt != null) {
            standardPreview.put("occurredAt", parsedOccurredAt.toString());
        }
        if (normalizedSeverity != null) {
            standardPreview.put("severity", normalizedSeverity);
        }
        var sample = new LinkedHashMap<String, Object>();
        sample.put("sourcePreview", sourcePreview);
        sample.put("standardEventPreview", standardPreview);
        if (dedupKeyPreview != null) {
            sample.put("dedupKeyPreview", dedupKeyPreview);
        }
        sample.put("errors", errors);
        sample.put("warnings", warnings);
        return sample;
    }

    private Object previewValue(String fieldName, String semanticType, Object value) {
        if (value == null) {
            return null;
        }
        if ("sensitive_value".equals(semanticType)) {
            return "******";
        }
        if (EXCLUDED_SEMANTICS.contains(semanticType) || excludedFieldPattern(fieldName)) {
            var text = String.valueOf(value);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("length", text.length());
            summary.put("sha256", sha256(text));
            return summary;
        }
        var text = String.valueOf(value);
        if (text.length() > MAX_VALUE_LENGTH) {
            return text.substring(0, MAX_VALUE_LENGTH);
        }
        return value;
    }

    private Object standardPreviewValue(String standardField, String sourceField, String semanticType, Object value) {
        var fieldName = excludedFieldPattern(standardField) || "detail".equals(standardField)
            ? standardField
            : sourceField;
        var effectiveSemanticType = "detail".equals(standardField) ? "detail" : semanticType;
        return previewValue(fieldName, effectiveSemanticType, value);
    }

    private Source loadSource(Map<String, Object> row, Map<String, Object> plan) {
        var dataSourceId = support.number(row.get("data_source_id"));
        var schemaTableId = longValue(plan.get("schemaTableId"));
        if (dataSourceId == null || schemaTableId == null) {
            throw new PlanBlockerException(
                "source_table_missing",
                "source_table",
                "Plan does not reference a scanned source table"
            );
        }
        var tableRows = jdbcTemplate.queryForList("""
            select ds.source_type, ds.config_json, st.schema_name, st.table_name
            from schema_tables st
            join data_sources ds on ds.id = st.data_source_id
            where st.id = ?
              and st.data_source_id = ?
              and coalesce(st.lifecycle_status, 'active') = 'active'
            """, schemaTableId, dataSourceId);
        if (tableRows.isEmpty()) {
            throw new PlanBlockerException("source_table_missing", "source_table", "Plan source table is not active");
        }
        var tableRow = tableRows.get(0);
        var selectedFields = selectedFields(plan);
        var metadata = loadFieldMetadata(schemaTableId, selectedFields);
        var activeFields = metadata.keySet();
        var missing = selectedFields.stream().filter(field -> !activeFields.contains(field)).toList();
        if (!missing.isEmpty()) {
            throw new PlanBlockerException(
                "source_fields_missing",
                "source_fields",
                "Plan references inactive source fields: " + missing
            );
        }
        return new Source(
            support.stringOrDefault(tableRow.get("source_type"), ""),
            tableRow.get("config_json"),
            support.stringOrNull(tableRow.get("schema_name")),
            support.stringOrDefault(tableRow.get("table_name"), support.stringOrDefault(plan.get("mainTable"), "")),
            selectedFields,
            metadata,
            plan
        );
    }

    private Map<String, FieldMetadata> loadFieldMetadata(Long schemaTableId, List<String> selectedFields) {
        var rows = jdbcTemplate.queryForList("""
            select field_name, semantic_type
            from schema_fields
            where schema_table_id = ?
              and coalesce(lifecycle_status, 'active') = 'active'
            order by ordinal_position nulls last, id
            """, schemaTableId);
        var selected = new LinkedHashSet<>(selectedFields);
        var metadata = new LinkedHashMap<String, FieldMetadata>();
        for (var row : rows) {
            var fieldName = support.stringOrNull(row.get("field_name"));
            if (fieldName != null && selected.contains(fieldName)) {
                metadata.put(fieldName, new FieldMetadata(fieldName, support.stringOrDefault(row.get("semantic_type"), "")));
            }
        }
        return metadata;
    }

    private List<String> selectedFields(Map<String, Object> plan) {
        var selected = new LinkedHashSet<String>();
        selected.addAll(fieldMappingSources(plan).keySet());
        selected.addAll(dedupFields(plan));
        var cursorField = support.stringOrNull(plan.get("cursorField"));
        if (cursorField != null) {
            selected.add(cursorField);
        }
        return new ArrayList<>(selected);
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

    private String sourceFieldForStandardField(Map<String, String> fieldMappings, String standardField) {
        for (var entry : fieldMappings.entrySet()) {
            if (standardField.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private List<String> dedupFields(Map<String, Object> plan) {
        if (!(plan.get("dedupStrategy") instanceof Map<?, ?> strategy)) {
            return List.of();
        }
        return stringList(strategy.get("fields"));
    }

    private String dedupKey(Map<String, Object> row, List<String> fields) {
        if (fields.isEmpty()) {
            return null;
        }
        var values = new ArrayList<String>();
        for (var field : fields) {
            if (blank(row.get(field))) {
                return null;
            }
            values.add(String.valueOf(row.get(field)));
        }
        return String.join("|", values);
    }

    private String normalizeSeverity(Object value) {
        var text = support.stringOrNull(value);
        if (text == null) {
            return null;
        }
        return NORMALIZED_SEVERITIES.get(text.toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> reportSkeleton(
        long planId,
        Long dataSourceId,
        int sampleLimit,
        Map<String, Object> precheck
    ) {
        var status = support.stringOrDefault(precheck.get("result"), "passed");
        var report = new LinkedHashMap<String, Object>();
        report.put("planId", planId);
        report.put("dataSourceId", dataSourceId);
        report.put("status", status);
        report.put("summary", summary(status, sampleLimit, 0, 0, 0, 0, 0));
        report.put("checks", precheck.getOrDefault("checks", List.of()));
        report.put("blockers", precheck.getOrDefault("blockers", List.of()));
        report.put("warnings", precheck.getOrDefault("warnings", List.of()));
        report.put("samples", List.of());
        report.put("errorsByType", Map.of());
        report.put("previewPolicy", previewPolicy());
        return report;
    }

    private Map<String, Object> summary(
        String status,
        int sampleLimit,
        int readCount,
        int successCount,
        int failedCount,
        int duplicateCount,
        int missingRequiredCount
    ) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("status", status);
        summary.put("sampleLimit", sampleLimit);
        summary.put("readCount", readCount);
        summary.put("successCount", successCount);
        summary.put("failedCount", failedCount);
        summary.put("duplicateCount", duplicateCount);
        summary.put("missingRequiredCount", missingRequiredCount);
        return summary;
    }

    private Map<String, Object> failedCheck(PlanBlockerException ex, String message) {
        var check = new LinkedHashMap<String, Object>();
        check.put("code", ex.checkCode());
        check.put("result", "failed");
        check.put("message", message);
        check.put("blockers", ex.blockers());
        return check;
    }

    private Map<String, Object> previewPolicy() {
        var policy = new LinkedHashMap<String, Object>();
        policy.put("mode", "mapped_fields_only");
        policy.put("maskedSensitiveValues", true);
        policy.put("maxSamples", MAX_PREVIEW_SAMPLES);
        policy.put("maxValueLength", MAX_VALUE_LENGTH);
        policy.put("excludedSemantics", EXCLUDED_SEMANTICS);
        policy.put("excludedFieldPatterns", EXCLUDED_FIELD_PATTERNS);
        return policy;
    }

    private Long insertRun(
        long planId,
        Long dataSourceId,
        String status,
        int sampleLimit,
        int readCount,
        int successCount,
        int failedCount,
        int duplicateCount,
        int missingRequiredCount,
        OffsetDateTime startedAt,
        String errorMessage,
        Map<String, Object> report
    ) {
        var finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        var durationMs = Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into ingestion_plan_shadow_runs(
                    ingestion_plan_id, data_source_id, status, sample_limit, read_count,
                    success_count, failed_count, duplicate_count, missing_required_count,
                    started_at, finished_at, duration_ms, error_message, report_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, planId);
            statement.setObject(2, dataSourceId);
            statement.setString(3, status);
            statement.setInt(4, sampleLimit);
            statement.setInt(5, readCount);
            statement.setInt(6, successCount);
            statement.setInt(7, failedCount);
            statement.setInt(8, duplicateCount);
            statement.setInt(9, missingRequiredCount);
            statement.setObject(10, startedAt);
            statement.setObject(11, finishedAt);
            statement.setLong(12, durationMs);
            statement.setString(13, errorMessage);
            statement.setString(14, toJson(report));
            return statement;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    private Map<String, Object> runRow(Long runId, boolean includeReport) {
        var row = jdbcTemplate.queryForMap("""
            select id, ingestion_plan_id, data_source_id, status, sample_limit, read_count,
                   success_count, failed_count, duplicate_count, missing_required_count,
                   started_at, finished_at, duration_ms, error_message, report_json, created_at, updated_at
            from ingestion_plan_shadow_runs
            where id = ?
            """, runId);
        return runRow(row, includeReport);
    }

    private Map<String, Object> runRow(Map<String, Object> row, boolean includeReport) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id"));
        result.put("ingestionPlanId", row.get("ingestion_plan_id"));
        result.put("dataSourceId", row.get("data_source_id"));
        result.put("status", row.get("status"));
        result.put("sampleLimit", row.get("sample_limit"));
        result.put("readCount", row.get("read_count"));
        result.put("successCount", row.get("success_count"));
        result.put("failedCount", row.get("failed_count"));
        result.put("duplicateCount", row.get("duplicate_count"));
        result.put("missingRequiredCount", row.get("missing_required_count"));
        result.put("startedAt", row.get("started_at"));
        result.put("finishedAt", row.get("finished_at"));
        result.put("durationMs", row.get("duration_ms"));
        result.put("errorMessage", row.get("error_message"));
        result.put("createdAt", row.get("created_at"));
        result.put("updatedAt", row.get("updated_at"));
        if (includeReport) {
            result.put("report", parseJson(row.get("report_json")));
        }
        return result;
    }

    private Map<String, Object> loadPlanRow(long planId) {
        var rows = jdbcTemplate.queryForList("""
            select id, data_source_id, scan_run_id, name, status, plan_json, created_at, updated_at
            from ingestion_plans
            where id = ?
            """, planId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + planId);
        }
        return rows.get(0);
    }

    private void ensurePlanExists(long planId) {
        var count = jdbcTemplate.queryForObject("select count(*) from ingestion_plans where id = ?", Long.class, planId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + planId);
        }
    }

    private int sampleLimit(IngestionPlanShadowRunRequest request) {
        var requested = request == null || request.sampleLimit() == null ? DEFAULT_SAMPLE_LIMIT : request.sampleLimit();
        return support.safeLimit(requested, MAX_SAMPLE_LIMIT);
    }

    private Long generatedId(GeneratedKeyHolder keyHolder) {
        var keys = keyHolder.getKeys();
        Number key = null;
        if (keys != null && keys.get("id") instanceof Number id) {
            key = id;
        } else if (keys != null && keys.get("ID") instanceof Number id) {
            key = id;
        } else {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("Insert did not return a generated id");
        }
        return key.longValue();
    }

    private Map<String, Object> parsePlan(Object value) {
        return parseJson(value);
    }

    private Map<String, Object> parseJson(Object value) {
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

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize shadow run report", ex);
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

    private boolean blank(Object value) {
        return support.stringOrNull(value) == null;
    }

    private boolean excludedFieldPattern(String fieldName) {
        var normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        return EXCLUDED_FIELD_PATTERNS.stream().anyMatch(normalized::contains);
    }

    private void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record Source(
        String sourceType,
        Object configJson,
        String schemaName,
        String tableName,
        List<String> selectedFields,
        Map<String, FieldMetadata> fieldMetadata,
        Map<String, Object> plan
    ) {
    }

    private record FieldMetadata(String fieldName, String semanticType) {
    }

    private record Analysis(
        String status,
        int successCount,
        int failedCount,
        int duplicateCount,
        int missingRequiredCount,
        Map<String, Object> report
    ) {
    }

    private static final class PlanBlockerException extends RuntimeException {
        private final String errorType;
        private final String checkCode;

        private PlanBlockerException(String errorType, String checkCode, String message) {
            super(message);
            this.errorType = errorType;
            this.checkCode = checkCode;
        }

        private String errorType() {
            return errorType;
        }

        private String checkCode() {
            return checkCode;
        }

        private List<String> blockers() {
            return List.of(errorType);
        }
    }
}
