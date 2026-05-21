package com.edsp.core.service;

import com.edsp.core.dto.CollectionTaskRequest;
import com.edsp.core.dto.IngestionRunFinishRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CollectionTaskService {
    private final JdbcTemplate jdbcTemplate;
    private final CoreRequestSupport support;
    private final ObjectMapper objectMapper;

    public CollectionTaskService(JdbcTemplate jdbcTemplate, CoreRequestSupport support, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.support = support;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> list(int limit) {
        return jdbcTemplate.queryForList("""
            select ct.id, ct.name, ct.task_type, ct.schedule_mode, ct.interval_seconds,
                   ct.status, ct.enabled, ct.last_run_at, ct.next_run_at,
                   ds.name as data_source_name, ds.source_type,
                   ca.name as adapter_name, ct.created_at, ct.updated_at
            from collection_tasks ct
            join data_sources ds on ds.id = ct.data_source_id
            left join collector_adapters ca on ca.id = ct.adapter_id
            order by ct.updated_at desc
            limit ?
            """, support.safeLimit(limit));
    }

    public Map<String, Object> create(CollectionTaskRequest request) {
        var configJson = support.jsonOrEmpty(request.configJson(), "configJson");
        var id = insertAndReturnId("""
            insert into collection_tasks(
                data_source_id, adapter_id, name, task_type, schedule_mode,
                interval_seconds, status, enabled, config_json
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """, request.dataSourceId(), request.adapterId(), request.name(),
            request.taskType(), request.scheduleMode(), request.intervalSeconds(), request.status(),
            request.enabled(), configJson);
        return Map.of("id", id);
    }

    public Map<String, Object> update(long id, CollectionTaskRequest request) {
        var configJson = support.jsonOrEmpty(request.configJson(), "configJson");
        jdbcTemplate.update("""
            update collection_tasks
            set data_source_id = ?, adapter_id = ?, name = ?, task_type = ?,
                schedule_mode = ?, interval_seconds = ?, status = ?, enabled = ?,
                config_json = cast(? as jsonb), updated_at = now()
            where id = ?
            """, request.dataSourceId(), request.adapterId(), request.name(), request.taskType(),
            request.scheduleMode(), request.intervalSeconds(), request.status(), request.enabled(),
            configJson, id);
        return Map.of("id", id);
    }

    @Transactional
    public Map<String, Object> startRun(long id, String runType) {
        var task = loadTask(id);
        var dataSourceId = support.number(task.get("data_source_id"));
        var runId = insertAndReturnId("""
            insert into ingestion_runs(task_id, data_source_id, run_type, status)
            values (?, ?, ?, 'running')
            """, id, dataSourceId, runType);
        jdbcTemplate.update("""
            update collection_tasks
            set status = 'running', last_run_at = now(), updated_at = now()
            where id = ?
            """, id);

        var records = buildSourceRecords(id, dataSourceId, runId, task);
        long successCount = 0;
        long failedCount = 0;
        var errors = new ArrayList<String>();
        for (var record : records) {
            try {
                writeRecord(record);
                successCount++;
            } catch (RuntimeException ex) {
                failedCount++;
                errors.add(record.tableName() + ": " + ex.getMessage());
            }
        }

        var status = failedCount == 0 ? "success" : "failed";
        var cursorAfter = "run:" + runId;
        var qualityReport = new LinkedHashMap<String, Object>();
        qualityReport.put("mode", "metadata_snapshot");
        qualityReport.put("tables", records.size());
        qualityReport.put("standardized", successCount);
        qualityReport.put("failed", failedCount);
        qualityReport.put("errors", errors);
        finishRunInternal(
            runId,
            status,
            cursorAfter,
            records.size(),
            successCount,
            failedCount,
            0,
            errors.isEmpty() ? null : String.join("; ", errors),
            toJson(qualityReport)
        );

        var result = new LinkedHashMap<String, Object>();
        result.put("id", runId);
        result.put("taskId", id);
        result.put("status", status);
        result.put("readCount", records.size());
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("skippedCount", 0);
        result.put("standardizedCount", successCount);
        return result;
    }

    public List<Map<String, Object>> runs(int limit) {
        return jdbcTemplate.queryForList("""
            select ir.id, ir.task_id, ct.name as task_name, ds.name as data_source_name,
                   ir.run_type, ir.status, ir.started_at, ir.finished_at,
                   ir.read_count, ir.success_count, ir.failed_count, ir.skipped_count,
                   ir.error_message
            from ingestion_runs ir
            left join collection_tasks ct on ct.id = ir.task_id
            left join data_sources ds on ds.id = ir.data_source_id
            order by ir.started_at desc
            limit ?
            """, support.safeLimit(limit));
    }

    @Transactional
    public Map<String, Object> finishRun(long runId, IngestionRunFinishRequest request) {
        var qualityReportJson = support.jsonOrEmpty(request.qualityReportJson(), "qualityReportJson");
        jdbcTemplate.update("""
            update ingestion_runs
            set status = ?, finished_at = now(), cursor_after = ?,
                read_count = ?, success_count = ?, failed_count = ?, skipped_count = ?,
                error_message = ?, quality_report_json = cast(? as jsonb)
            where id = ?
            """, request.status(), request.cursorAfter(), request.readCount(), request.successCount(),
            request.failedCount(), request.skippedCount(), request.errorMessage(), qualityReportJson, runId);
        jdbcTemplate.update("""
            update collection_tasks
            set status = ?, updated_at = now()
            where id = (select task_id from ingestion_runs where id = ?)
            """, taskStatus(request.status()), runId);
        updateCursor(runId, request.cursorAfter());
        return Map.of("id", runId);
    }

    private void updateCursor(long runId, String cursorAfter) {
        if (cursorAfter == null || cursorAfter.isBlank()) {
            return;
        }
        var taskId = jdbcTemplate.queryForObject(
            "select task_id from ingestion_runs where id = ?",
            Long.class,
            runId
        );
        var updated = jdbcTemplate.update("""
            update ingestion_cursors
            set cursor_value = ?, updated_at = now()
            where task_id = ? and cursor_key = 'default'
            """, cursorAfter, taskId);
        if (updated == 0) {
            jdbcTemplate.update("""
                insert into ingestion_cursors(task_id, cursor_key, cursor_value)
                values (?, 'default', ?)
                """, taskId, cursorAfter);
        }
    }

    private Map<String, Object> loadTask(long id) {
        var rows = jdbcTemplate.queryForList("""
            select ct.id, ct.data_source_id, ct.name, ct.config_json,
                   ds.name as data_source_name, ds.source_type, ds.connection_kind
            from collection_tasks ct
            join data_sources ds on ds.id = ct.data_source_id
            where ct.id = ?
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Collection task not found: " + id);
        }
        return rows.get(0);
    }

    private List<SourceRecord> buildSourceRecords(
        long taskId,
        long dataSourceId,
        long runId,
        Map<String, Object> task
    ) {
        var rows = jdbcTemplate.queryForList("""
            select st.id as table_id, st.table_name, st.category,
                   sf.field_name, sf.field_type, sf.sample_value, sf.semantic_type,
                   fm.standard_field
            from schema_tables st
            join schema_fields sf on sf.schema_table_id = st.id
            left join field_mappings fm
                on fm.schema_table_id = st.id and fm.source_field = sf.field_name
            where st.data_source_id = ?
              and lower(st.confirmation_status) in ('confirmed', 'accepted', 'auto_accepted')
              and coalesce(st.lifecycle_status, 'active') = 'active'
              and coalesce(sf.lifecycle_status, 'active') = 'active'
            order by st.updated_at desc, st.id, sf.ordinal_position, sf.id
            """, dataSourceId);

        var tables = new LinkedHashMap<Long, SourceTable>();
        for (var row : rows) {
            var tableId = support.number(row.get("table_id"));
            if (tableId == null) {
                continue;
            }
            var table = tables.computeIfAbsent(tableId, ignored -> new SourceTable(
                tableId,
                support.stringOrDefault(row.get("table_name"), "unknown_table"),
                support.stringOrDefault(row.get("category"), "event"),
                new ArrayList<>()
            ));
            table.fields().add(new SourceField(
                support.stringOrDefault(row.get("field_name"), "field"),
                support.stringOrDefault(row.get("field_type"), "text"),
                support.stringOrNull(row.get("sample_value")),
                support.stringOrNull(row.get("semantic_type")),
                support.stringOrNull(row.get("standard_field"))
            ));
        }

        var records = new ArrayList<SourceRecord>();
        var sourceSystem = sourceSystem(task);
        var index = 1;
        for (var table : tables.values()) {
            records.add(toSourceRecord(taskId, dataSourceId, runId, sourceSystem, table, index++));
        }
        return records;
    }

    private SourceRecord toSourceRecord(
        long taskId,
        long dataSourceId,
        long runId,
        String sourceSystem,
        SourceTable table,
        int index
    ) {
        var occurredAt = OffsetDateTime.now().minusMinutes(index * 5L);
        var sourceValues = new LinkedHashMap<String, Object>();
        var mappedValues = new LinkedHashMap<String, Object>();
        for (var field : table.fields()) {
            var value = sourceValue(field, table, taskId, runId, index, occurredAt);
            sourceValues.put(field.fieldName(), value);
            var standardField = support.blankToNull(field.standardField());
            if (standardField != null) {
                mappedValues.put(canonical(standardField), value);
            }
        }

        var externalId = text(mappedValues.get("externalid"));
        if (externalId == null) {
            externalId = sourceSystem + "-" + taskId + "-" + runId + "-" + table.tableId();
        }
        var eventType = text(mappedValues.get("eventtype"));
        if (eventType == null) {
            eventType = table.category();
        }
        var occurredAtText = text(mappedValues.get("occurredat"));
        var parsedOccurredAt = occurredAtText == null ? occurredAt : support.parseTime(occurredAtText);
        var severity = severity(text(mappedValues.get("severity")));
        var riskScore = riskScore(severity, text(mappedValues.get("riskscore")));

        var payload = new LinkedHashMap<String, Object>();
        payload.put("table", table.tableName());
        payload.put("category", table.category());
        payload.put("fields", sourceValues);

        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("table", table.tableName());
        normalized.put("category", table.category());
        normalized.put("mapped", mappedValues);
        normalized.put("payload", sourceValues);

        return new SourceRecord(
            dataSourceId,
            taskId,
            runId,
            sourceSystem,
            externalId,
            eventType,
            parsedOccurredAt,
            text(mappedValues.get("actor")),
            text(mappedValues.get("assetref")),
            table.category(),
            firstText(mappedValues.get("subjectref"), mappedValues.get("assetref"), externalId),
            text(mappedValues.get("action")),
            firstText(mappedValues.get("result"), "detected"),
            severity,
            riskScore,
            toJson(payload),
            toJson(normalized),
            table.tableName()
        );
    }

    private void writeRecord(SourceRecord record) {
        var rawId = insertAndReturnId("""
            insert into raw_events(
                data_source_id, task_id, run_id, source_system, external_id,
                event_type, occurred_at, payload_json, status
            )
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), 'received')
            """, record.dataSourceId(), record.taskId(), record.runId(), record.sourceSystem(),
            record.externalId(), record.eventType(), record.occurredAt(), record.payloadJson());
        var standardId = upsertStandardEvent(rawId, record);
        jdbcTemplate.update("""
            update raw_events
            set status = 'standardized', standard_event_id = ?
            where id = ?
            """, standardId, rawId);
    }

    private Long upsertStandardEvent(long rawId, SourceRecord record) {
        var dedupKey = support.dedupKey(record.sourceSystem(), record.externalId(), record.eventType(),
            record.occurredAt(), record.actor(), record.assetRef(), record.subjectRef());
        var existingRows = jdbcTemplate.queryForList("""
            select id
            from standard_events
            where source_system = ? and external_id = ?
            """, record.sourceSystem(), record.externalId());
        if (!existingRows.isEmpty()) {
            var existingId = support.number(existingRows.get(0).get("id"));
            jdbcTemplate.update("""
                update standard_events
                set raw_event_id = ?, data_source_id = ?, event_type = ?, occurred_at = ?,
                    actor = ?, asset_ref = ?, subject_type = ?, subject_ref = ?,
                    action = ?, result = ?, severity = ?, risk_score = ?,
                    normalized_json = cast(? as jsonb), extra_json = cast(? as jsonb),
                    dedup_key = ?, updated_at = now()
                where id = ?
                """, rawId, record.dataSourceId(), record.eventType(), record.occurredAt(),
                record.actor(), record.assetRef(), record.subjectType(), record.subjectRef(),
                record.action(), record.result(), record.severity(), record.riskScore(),
                record.normalizedJson(), record.payloadJson(), dedupKey, existingId);
            return existingId;
        }

        return insertAndReturnId("""
            insert into standard_events(
                raw_event_id, data_source_id, source_system, external_id, event_type,
                occurred_at, actor, asset_ref, subject_type, subject_ref, action, result,
                severity, risk_score, normalized_json, extra_json, dedup_key
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
            """, rawId, record.dataSourceId(), record.sourceSystem(), record.externalId(),
            record.eventType(), record.occurredAt(), record.actor(), record.assetRef(),
            record.subjectType(), record.subjectRef(), record.action(), record.result(),
            record.severity(), record.riskScore(), record.normalizedJson(), record.payloadJson(), dedupKey);
    }

    private void finishRunInternal(
        long runId,
        String status,
        String cursorAfter,
        long readCount,
        long successCount,
        long failedCount,
        long skippedCount,
        String errorMessage,
        String qualityReportJson
    ) {
        jdbcTemplate.update("""
            update ingestion_runs
            set status = ?, finished_at = now(), cursor_after = ?,
                read_count = ?, success_count = ?, failed_count = ?, skipped_count = ?,
                error_message = ?, quality_report_json = cast(? as jsonb)
            where id = ?
            """, status, cursorAfter, readCount, successCount, failedCount, skippedCount,
            errorMessage, qualityReportJson, runId);
        jdbcTemplate.update("""
            update collection_tasks
            set status = ?, updated_at = now()
            where id = (select task_id from ingestion_runs where id = ?)
            """, taskStatus(status), runId);
        updateCursor(runId, cursorAfter);
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

    private String sourceSystem(Map<String, Object> task) {
        return canonical(firstText(task.get("source_type"), task.get("connection_kind"), task.get("data_source_name")));
    }

    private String sourceValue(
        SourceField field,
        SourceTable table,
        long taskId,
        long runId,
        int index,
        OffsetDateTime occurredAt
    ) {
        var sampleValue = support.blankToNull(field.sampleValue());
        if (sampleValue != null) {
            return sampleValue;
        }
        var key = canonical(firstText(field.standardField(), field.semanticType(), field.fieldName()));
        if (key.contains("externalid") || key.endsWith("id")) {
            return table.tableName() + "-" + taskId + "-" + runId + "-" + index;
        }
        if (key.contains("time") || key.contains("occurred")) {
            return occurredAt.toString();
        }
        if (key.contains("actor") || key.contains("user") || key.contains("account")) {
            return "demo.user";
        }
        if (key.contains("asset") || key.contains("host") || key.contains("device")) {
            return "demo-host-" + index;
        }
        if (key.contains("severity") || key.contains("level")) {
            return "medium";
        }
        if (key.contains("action") || key.contains("operation")) {
            return "detected";
        }
        if (key.contains("eventtype") || key.contains("type")) {
            return table.category();
        }
        return field.fieldName() + "_sample";
    }

    private String severity(String value) {
        var normalized = canonical(value);
        return switch (normalized) {
            case "critical", "high", "medium", "low", "info" -> normalized;
            case "urgent", "fatal" -> "critical";
            case "warn", "warning" -> "medium";
            default -> "info";
        };
    }

    private Integer riskScore(String severity, String value) {
        var text = support.blankToNull(value);
        if (text != null) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                // Fall back to severity-derived score.
            }
        }
        return switch (severity) {
            case "critical" -> 95;
            case "high" -> 80;
            case "medium" -> 55;
            case "low" -> 25;
            default -> 10;
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize collection payload", ex);
        }
    }

    private String canonical(Object value) {
        var text = support.stringOrDefault(value, "external");
        return text.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return support.stringOrNull(value);
    }

    private String firstText(Object... values) {
        for (var value : values) {
            var text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String taskStatus(String runStatus) {
        return switch (runStatus) {
            case "success" -> "idle";
            case "failed" -> "failed";
            default -> runStatus;
        };
    }

    private record SourceTable(
        Long tableId,
        String tableName,
        String category,
        List<SourceField> fields
    ) {
    }

    private record SourceField(
        String fieldName,
        String fieldType,
        String sampleValue,
        String semanticType,
        String standardField
    ) {
    }

    private record SourceRecord(
        Long dataSourceId,
        Long taskId,
        Long runId,
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
        String payloadJson,
        String normalizedJson,
        String tableName
    ) {
    }
}
