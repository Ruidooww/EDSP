package com.edsp.core.service;

import com.edsp.core.dto.IngestionPlanSyncOnceRequest;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionPlanSyncOnceService {
    private static final int DEFAULT_SAMPLE_LIMIT = 50;
    private static final int MAX_SAMPLE_LIMIT = 100;
    private static final Set<String> PLAN_SYNC_STATUSES = Set.of("approved", "shadow_ready");
    private static final Map<String, String> NORMALIZED_SEVERITIES = Map.ofEntries(
        Map.entry("critical", "critical"),
        Map.entry("1", "critical"),
        Map.entry("high", "high"),
        Map.entry("2", "high"),
        Map.entry("medium", "medium"),
        Map.entry("warning", "medium"),
        Map.entry("3", "medium"),
        Map.entry("low", "low"),
        Map.entry("4", "low"),
        Map.entry("info", "info")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;
    private final JdbcShadowSampleService sampleService;
    private final StandardEventDedupService standardEventDedupService;

    public IngestionPlanSyncOnceService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support,
        JdbcShadowSampleService sampleService,
        StandardEventDedupService standardEventDedupService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
        this.sampleService = sampleService;
        this.standardEventDedupService = standardEventDedupService;
    }

    @Transactional
    public Map<String, Object> syncOnce(long activationId, IngestionPlanSyncOnceRequest request) {
        var activation = loadActiveActivation(activationId);
        var planId = support.number(activation.get("ingestion_plan_id"));
        var dataSourceId = support.number(activation.get("data_source_id"));
        var shadowRunId = support.number(activation.get("shadow_run_id"));
        var plan = loadPlan(planId);
        validateActivationDataSource(planId, dataSourceId, support.number(plan.get("data_source_id")));
        validatePlanAndShadowRun(planId, dataSourceId, shadowRunId, support.stringOrDefault(plan.get("status"), ""));

        var sampleLimit = sampleLimit(request);
        var startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        var ingestionRunId = insertIngestionRun(dataSourceId);
        try {
            var planJson = parseJson(plan.get("plan_json"));
            var source = loadSource(dataSourceId, planJson);
            var rows = sampleRows(source, sampleLimit);
            var result = processRows(ingestionRunId, dataSourceId, source, rows);
            var report = report(planId, activationId, ingestionRunId, sampleLimit, result);
            var status = result.status();
            finishIngestionRun(ingestionRunId, status, result, report, null);
            var syncRunId = insertSyncRun(planId, activationId, dataSourceId, shadowRunId, ingestionRunId,
                status, sampleLimit, result, startedAt, null, report);
            return syncRunRow(syncRunId, true);
        } catch (PlanBlockerException ex) {
            var result = SyncResult.empty("blocked");
            var report = report(planId, activationId, ingestionRunId, sampleLimit, result);
            report.put("blockers", ex.blockers());
            report.put("errorsByType", Map.of(ex.errorType(), 1));
            report.put("errorMessage", ex.getMessage());
            finishIngestionRun(ingestionRunId, "blocked", result, report, ex.getMessage());
            var syncRunId = insertSyncRun(planId, activationId, dataSourceId, shadowRunId, ingestionRunId,
                "blocked", sampleLimit, result, startedAt, ex.getMessage(), report);
            return syncRunRow(syncRunId, true);
        } catch (RuntimeException ex) {
            var message = support.stringOrDefault(ex.getMessage(), "Sync once failed");
            var result = SyncResult.empty("failed");
            var report = report(planId, activationId, ingestionRunId, sampleLimit, result);
            report.put("errorsByType", Map.of("execution_failed", 1));
            report.put("errorMessage", message);
            finishIngestionRun(ingestionRunId, "failed", result, report, message);
            var syncRunId = insertSyncRun(planId, activationId, dataSourceId, shadowRunId, ingestionRunId,
                "failed", sampleLimit, result, startedAt, message, report);
            return syncRunRow(syncRunId, true);
        }
    }

    public List<Map<String, Object>> listByPlan(long planId, int limit) {
        ensurePlanExists(planId);
        return jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, activation_id, data_source_id, shadow_run_id, ingestion_run_id,
                   status, sample_limit, read_count, success_count, failed_count, duplicate_count,
                   raw_count, standard_count, started_at, finished_at, duration_ms, error_message,
                   report_json, created_at, updated_at
            from ingestion_plan_sync_runs
            where ingestion_plan_id = ?
            order by created_at desc, id desc
            limit ?
            """, planId, support.safeLimit(limit, 50)).stream()
            .map(row -> syncRunRow(row, true))
            .toList();
    }

    private SyncResult processRows(long ingestionRunId, Long dataSourceId, Source source, List<Map<String, Object>> rows) {
        var mappings = fieldMappingSources(source.plan());
        var dedupFields = dedupFields(source.plan());
        var errorsByType = new LinkedHashMap<String, Integer>();
        var warnings = new LinkedHashSet<String>();
        var readCount = rows.size();
        var successCount = 0;
        var failedCount = 0;
        var duplicateCount = 0;
        var rawCount = 0;
        var standardCount = 0;

        for (var row : rows) {
            var standard = standardRecord(dataSourceId, source, mappings, dedupFields, row);
            var rawId = insertRawEvent(ingestionRunId, dataSourceId, source, standard, row);
            rawCount++;
            if (!standard.errors().isEmpty()) {
                failedCount++;
                standard.errors().forEach(error -> increment(errorsByType, error));
                updateRawStatus(rawId, "standardize_failed", null);
                warnings.add("partial_row_failure");
                continue;
            }
            var existingId = standardEventDedupService.findExistingStandardEventId(
                standard.dedupKey(),
                dataSourceId,
                standard.sourceSystem(),
                standard.externalId()
            );
            if (existingId != null) {
                duplicateCount++;
                updateRawStatus(rawId, "standardized", existingId);
            } else {
                var standardId = insertStandardEvent(rawId, dataSourceId, standard);
                updateRawStatus(rawId, "standardized", standardId);
                standardCount++;
            }
            successCount++;
        }
        if (readCount == 0) {
            warnings.add("no_source_rows");
        }
        var status = failedCount > 0 || !warnings.isEmpty() ? "warning" : "passed";
        return new SyncResult(status, readCount, successCount, failedCount, duplicateCount, rawCount,
            standardCount, new ArrayList<>(warnings), errorsByType);
    }

    private List<Map<String, Object>> sampleRows(Source source, int sampleLimit) {
        try {
            return sampleService.sample(
                source.sourceType(),
                source.configJson(),
                source.schemaName(),
                source.tableName(),
                source.selectedFields(),
                sampleLimit
            );
        } catch (RuntimeException ex) {
            var blocker = sampleBlocker(ex);
            if (blocker != null) {
                throw blocker;
            }
            throw ex;
        }
    }

    private PlanBlockerException sampleBlocker(RuntimeException ex) {
        var message = support.stringOrDefault(ex.getMessage(), "").toLowerCase(Locale.ROOT);
        if ((message.contains("table") && message.contains("not found"))
            || (message.contains("relation") && message.contains("does not exist"))
            || message.contains("invalid object name")) {
            return new PlanBlockerException("source_table_missing", "Source table is not available");
        }
        if ((message.contains("column") && message.contains("not found"))
            || (message.contains("column") && message.contains("does not exist"))
            || message.contains("invalid column name")
            || message.contains("unknown column")) {
            return new PlanBlockerException("source_fields_missing", "Source fields are not available");
        }
        return null;
    }

    private StandardRecord standardRecord(
        Long dataSourceId,
        Source source,
        Map<String, String> mappings,
        List<String> dedupFields,
        Map<String, Object> row
    ) {
        var values = new LinkedHashMap<String, Object>();
        mappings.forEach((sourceField, standardField) -> values.put(standardField, row.get(sourceField)));
        var errors = new ArrayList<String>();
        var occurredAt = parseRequiredTime(values.get("occurredAt"), errors);
        var severity = normalizeSeverity(values.get("severity"), errors);
        var sourceSystem = sourceSystem(dataSourceId, source);
        var externalId = support.stringOrNull(values.get("externalId"));
        var eventType = support.stringOrDefault(first(values.get("eventType"), values.get("title")), "ingestion_plan_event");
        var actor = support.stringOrNull(values.get("actor"));
        var assetRef = support.stringOrNull(values.get("assetRef"));
        var subjectRef = support.stringOrNull(values.get("subjectRef"));
        var dedupKey = dedupKey(dataSourceId, source, row, dedupFields, sourceSystem, externalId, eventType,
            occurredAt, actor, assetRef, subjectRef);
        if (dedupKey == null) {
            errors.add("dedup_key_missing");
        }
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("sourceTable", source.tableName());
        normalized.put("mapped", values);
        var extra = new LinkedHashMap<String, Object>();
        extra.put("syncMode", "sync_once");
        extra.put("sourceTable", source.tableName());
        extra.put("dataSourceId", dataSourceId);
        return new StandardRecord(
            sourceSystem,
            externalId,
            eventType,
            occurredAt,
            actor,
            assetRef,
            support.stringOrDefault(values.get("subjectType"), "event"),
            subjectRef == null ? assetRef : subjectRef,
            support.stringOrNull(values.get("action")),
            support.stringOrDefault(values.get("result"), "detected"),
            severity,
            riskScore(severity),
            dedupKey,
            toJson(normalized),
            toJson(extra),
            errors
        );
    }

    private OffsetDateTime parseRequiredTime(Object value, List<String> errors) {
        var text = support.stringOrNull(value);
        if (text == null) {
            errors.add("missing_occurred_at");
            return null;
        }
        try {
            return support.parseTime(text);
        } catch (RuntimeException ex) {
            errors.add("invalid_time_format");
            return null;
        }
    }

    private String normalizeSeverity(Object value, List<String> errors) {
        var text = support.stringOrNull(value);
        if (text == null) {
            return "info";
        }
        var severity = NORMALIZED_SEVERITIES.get(text.toLowerCase(Locale.ROOT));
        if (severity == null) {
            errors.add("severity_unrecognized");
            return "info";
        }
        return severity;
    }

    private String dedupKey(
        Long dataSourceId,
        Source source,
        Map<String, Object> row,
        List<String> dedupFields,
        String sourceSystem,
        String externalId,
        String eventType,
        OffsetDateTime occurredAt,
        String actor,
        String assetRef,
        String subjectRef
    ) {
        if (dedupFields.isEmpty()) {
            return support.dedupKey(sourceSystem, externalId, eventType, occurredAt, actor, assetRef, subjectRef);
        }
        for (var field : dedupFields) {
            if (support.stringOrNull(row.get(field)) == null) {
                return null;
            }
        }
        var values = new ArrayList<String>();
        for (var field : dedupFields) {
            values.add(field + "=" + row.get(field));
        }
        var schemaTableId = longValue(source.plan().get("schemaTableId"));
        return sha256(String.join("|",
            "sync_once",
            "data_source:" + dataSourceId,
            "schema_table:" + schemaTableId,
            "table:" + source.tableName(),
            String.join("|", values)
        ));
    }

    private String sourceSystem(Long dataSourceId, Source source) {
        if (dataSourceId == null) {
            return "external";
        }
        var schemaTableId = longValue(source.plan().get("schemaTableId"));
        return "ds:%d:st:%s".formatted(dataSourceId, schemaTableId);
    }

    private Long insertRawEvent(
        long ingestionRunId,
        Long dataSourceId,
        Source source,
        StandardRecord standard,
        Map<String, Object> row
    ) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("mode", "sync_once");
        payload.put("table", source.tableName());
        payload.put("fields", row);
        var payloadJson = toJson(payload);
        return insertAndReturnId("""
            insert into raw_events(
                data_source_id, run_id, source_system, external_id, event_type,
                occurred_at, payload_json, payload_hash, status
            )
            values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, 'received')
            """, dataSourceId, ingestionRunId, standard.sourceSystem(), standard.externalId(),
            standard.eventType(), standard.occurredAt(), payloadJson, sha256(payloadJson));
    }

    private Long insertStandardEvent(long rawId, Long dataSourceId, StandardRecord record) {
        return insertAndReturnId("""
            insert into standard_events(
                raw_event_id, data_source_id, source_system, external_id, event_type,
                occurred_at, actor, asset_ref, subject_type, subject_ref, action, result,
                severity, risk_score, normalized_json, extra_json, dedup_key
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
            """, rawId, dataSourceId, record.sourceSystem(), record.externalId(), record.eventType(),
            record.occurredAt(), record.actor(), record.assetRef(), record.subjectType(), record.subjectRef(),
            record.action(), record.result(), record.severity(), record.riskScore(), record.normalizedJson(),
            record.extraJson(), record.dedupKey());
    }

    private void updateRawStatus(long rawId, String status, Long standardEventId) {
        jdbcTemplate.update("""
            update raw_events
            set status = ?, standard_event_id = ?
            where id = ?
            """, status, standardEventId, rawId);
    }

    private Source loadSource(Long dataSourceId, Map<String, Object> plan) {
        var schemaTableId = longValue(plan.get("schemaTableId"));
        if (dataSourceId == null || schemaTableId == null) {
            throw new PlanBlockerException("source_table_missing", "Plan does not reference a scanned source table");
        }
        var rows = jdbcTemplate.queryForList("""
            select ds.source_type, ds.config_json, st.schema_name, st.table_name
            from schema_tables st
            join data_sources ds on ds.id = st.data_source_id
            where st.id = ?
              and st.data_source_id = ?
              and coalesce(st.lifecycle_status, 'active') = 'active'
            """, schemaTableId, dataSourceId);
        if (rows.isEmpty()) {
            throw new PlanBlockerException("source_table_missing", "Plan source table is not active");
        }
        var selectedFields = selectedFields(plan);
        var activeFields = loadActiveFields(schemaTableId);
        var missing = selectedFields.stream().filter(field -> !activeFields.contains(field)).toList();
        if (!missing.isEmpty()) {
            throw new PlanBlockerException("source_fields_missing", "Plan references inactive source fields: " + missing);
        }
        var row = rows.get(0);
        return new Source(
            support.stringOrDefault(row.get("source_type"), ""),
            row.get("config_json"),
            support.stringOrNull(row.get("schema_name")),
            support.stringOrDefault(row.get("table_name"), support.stringOrDefault(plan.get("mainTable"), "")),
            selectedFields,
            plan
        );
    }

    private void validateActivationDataSource(Long planId, Long activationDataSourceId, Long planDataSourceId) {
        if (activationDataSourceId == null || planDataSourceId == null || !activationDataSourceId.equals(planDataSourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active activation data source does not match ingestion plan: " + planId);
        }
    }

    private void validatePlanAndShadowRun(Long planId, Long dataSourceId, Long shadowRunId, String planStatus) {
        if (!PLAN_SYNC_STATUSES.contains(planStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingestion plan status is not syncable: " + planStatus);
        }
        var latest = jdbcTemplate.queryForList("""
            select id, data_source_id, status
            from ingestion_plan_shadow_runs
            where ingestion_plan_id = ?
            order by created_at desc, id desc
            limit 1
            """, planId);
        if (latest.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingestion plan has no Shadow Run: " + planId);
        }
        var latestRun = latest.get(0);
        var latestRunId = support.number(latestRun.get("id"));
        if (latestRunId == null || shadowRunId == null || latestRunId.longValue() != shadowRunId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active activation must reference the latest Shadow Run");
        }
        var latestStatus = support.stringOrDefault(latestRun.get("status"), "");
        if (!"passed".equals(latestStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latest Shadow Run must be passed before sync once: " + latestStatus);
        }
        var latestDataSourceId = support.number(latestRun.get("data_source_id"));
        if (dataSourceId == null || latestDataSourceId == null || !dataSourceId.equals(latestDataSourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latest Shadow Run data source does not match active activation");
        }
    }

    private Map<String, Object> loadActiveActivation(long activationId) {
        var rows = jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, shadow_run_id, status
            from ingestion_plan_activations
            where id = ?
            """, activationId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan activation not found: " + activationId);
        }
        var activation = rows.get(0);
        if (!"active".equals(support.stringOrDefault(activation.get("status"), ""))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only active activation can execute sync once");
        }
        return activation;
    }

    private Map<String, Object> loadPlan(Long planId) {
        var rows = jdbcTemplate.queryForList("""
            select id, data_source_id, status, plan_json
            from ingestion_plans
            where id = ?
            """, planId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + planId);
        }
        return rows.get(0);
    }

    private Set<String> loadActiveFields(Long schemaTableId) {
        var rows = jdbcTemplate.queryForList("""
            select field_name
            from schema_fields
            where schema_table_id = ?
              and coalesce(lifecycle_status, 'active') = 'active'
            """, schemaTableId);
        var fields = new LinkedHashSet<String>();
        for (var row : rows) {
            var fieldName = support.stringOrNull(row.get("field_name"));
            if (fieldName != null) {
                fields.add(fieldName);
            }
        }
        return fields;
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
                if (item instanceof Map<?, ?> mapping) {
                    var sourceField = support.stringOrNull(mapping.get("sourceField"));
                    var standardField = support.stringOrNull(mapping.get("standardField"));
                    if (sourceField != null && standardField != null) {
                        mappings.put(sourceField, standardField);
                    }
                }
            }
        }
        return mappings;
    }

    private List<String> dedupFields(Map<String, Object> plan) {
        if (!(plan.get("dedupStrategy") instanceof Map<?, ?> strategy)) {
            return List.of();
        }
        return stringList(strategy.get("fields"));
    }

    private Long insertIngestionRun(Long dataSourceId) {
        return insertAndReturnId("""
            insert into ingestion_runs(data_source_id, run_type, status, quality_report_json)
            values (?, 'sync_once', 'running', cast('{}' as jsonb))
            """, dataSourceId);
    }

    private void finishIngestionRun(
        Long ingestionRunId,
        String status,
        SyncResult result,
        Map<String, Object> report,
        String errorMessage
    ) {
        jdbcTemplate.update("""
            update ingestion_runs
            set status = ?, finished_at = now(), read_count = ?, success_count = ?,
                failed_count = ?, skipped_count = ?, error_message = ?,
                quality_report_json = cast(? as jsonb)
            where id = ?
            """, ingestionRunStatus(status), result.readCount(), result.successCount(), result.failedCount(),
            result.duplicateCount(), errorMessage, toJson(report), ingestionRunId);
    }

    private Long insertSyncRun(
        Long planId,
        Long activationId,
        Long dataSourceId,
        Long shadowRunId,
        Long ingestionRunId,
        String status,
        int sampleLimit,
        SyncResult result,
        OffsetDateTime startedAt,
        String errorMessage,
        Map<String, Object> report
    ) {
        var finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        var durationMs = Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        return insertAndReturnId("""
            insert into ingestion_plan_sync_runs(
                ingestion_plan_id, activation_id, data_source_id, shadow_run_id, ingestion_run_id,
                status, sample_limit, read_count, success_count, failed_count, duplicate_count,
                raw_count, standard_count, started_at, finished_at, duration_ms, error_message, report_json
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """, planId, activationId, dataSourceId, shadowRunId, ingestionRunId, status, sampleLimit,
            result.readCount(), result.successCount(), result.failedCount(), result.duplicateCount(),
            result.rawCount(), result.standardCount(), startedAt, finishedAt, durationMs, errorMessage,
            toJson(report));
    }

    private Map<String, Object> report(
        Long planId,
        Long activationId,
        Long ingestionRunId,
        int sampleLimit,
        SyncResult result
    ) {
        var report = new LinkedHashMap<String, Object>();
        report.put("mode", "sync_once");
        report.put("boundary", "Sync Once writes raw_events and standard_events only; no alerts or notifications");
        report.put("planId", planId);
        report.put("activationId", activationId);
        report.put("ingestionRunId", ingestionRunId);
        report.put("status", result.status());
        report.put("sampleLimit", sampleLimit);
        report.put("readCount", result.readCount());
        report.put("successCount", result.successCount());
        report.put("failedCount", result.failedCount());
        report.put("duplicateCount", result.duplicateCount());
        report.put("rawCount", result.rawCount());
        report.put("standardCount", result.standardCount());
        report.put("warnings", result.warnings());
        report.put("errorsByType", result.errorsByType());
        return report;
    }

    private Map<String, Object> syncRunRow(Long syncRunId, boolean includeReport) {
        var row = jdbcTemplate.queryForMap("""
            select id, ingestion_plan_id, activation_id, data_source_id, shadow_run_id, ingestion_run_id,
                   status, sample_limit, read_count, success_count, failed_count, duplicate_count,
                   raw_count, standard_count, started_at, finished_at, duration_ms, error_message,
                   report_json, created_at, updated_at
            from ingestion_plan_sync_runs
            where id = ?
            """, syncRunId);
        return syncRunRow(row, includeReport);
    }

    private Map<String, Object> syncRunRow(Map<String, Object> row, boolean includeReport) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id"));
        result.put("ingestionPlanId", row.get("ingestion_plan_id"));
        result.put("activationId", row.get("activation_id"));
        result.put("dataSourceId", row.get("data_source_id"));
        result.put("shadowRunId", row.get("shadow_run_id"));
        result.put("ingestionRunId", row.get("ingestion_run_id"));
        result.put("status", row.get("status"));
        result.put("sampleLimit", row.get("sample_limit"));
        result.put("readCount", row.get("read_count"));
        result.put("successCount", row.get("success_count"));
        result.put("failedCount", row.get("failed_count"));
        result.put("duplicateCount", row.get("duplicate_count"));
        result.put("rawCount", row.get("raw_count"));
        result.put("standardCount", row.get("standard_count"));
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

    private void ensurePlanExists(long planId) {
        var count = jdbcTemplate.queryForObject("select count(*) from ingestion_plans where id = ?", Long.class, planId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + planId);
        }
    }

    private int sampleLimit(IngestionPlanSyncOnceRequest request) {
        var requested = request == null || request.sampleLimit() == null ? DEFAULT_SAMPLE_LIMIT : request.sampleLimit();
        return support.safeLimit(requested, MAX_SAMPLE_LIMIT);
    }

    private String ingestionRunStatus(String status) {
        return switch (status) {
            case "passed" -> "success";
            default -> status;
        };
    }

    private Integer riskScore(String severity) {
        return switch (severity) {
            case "critical" -> 95;
            case "high" -> 80;
            case "medium" -> 55;
            case "low" -> 25;
            default -> 10;
        };
    }

    private Object first(Object... values) {
        for (var value : values) {
            if (support.stringOrNull(value) != null) {
                return value;
            }
        }
        return null;
    }

    private Long insertAndReturnId(String sql, Object... args) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (var index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
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

    private void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize sync once payload", ex);
        }
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
        Map<String, Object> plan
    ) {
    }

    private record StandardRecord(
        String sourceSystem,
        String externalId,
        String eventType,
        OffsetDateTime occurredAt,
        String actor,
        String assetRef,
        String subjectType,
        String subjectRef,
        String action,
        String result,
        String severity,
        Integer riskScore,
        String dedupKey,
        String normalizedJson,
        String extraJson,
        List<String> errors
    ) {
    }

    private record SyncResult(
        String status,
        int readCount,
        int successCount,
        int failedCount,
        int duplicateCount,
        int rawCount,
        int standardCount,
        List<String> warnings,
        Map<String, Integer> errorsByType
    ) {
        private static SyncResult empty(String status) {
            return new SyncResult(status, 0, 0, 0, 0, 0, 0, List.of(), Map.of());
        }
    }

    private static final class PlanBlockerException extends RuntimeException {
        private final String errorType;

        private PlanBlockerException(String errorType, String message) {
            super(message);
            this.errorType = errorType;
        }

        private String errorType() {
            return errorType;
        }

        private List<String> blockers() {
            return List.of(errorType);
        }
    }
}
