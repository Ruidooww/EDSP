package com.edsp.core.service;

import com.edsp.core.dto.SchemaScanExecuteRequest;
import com.edsp.core.dto.SchemaChangeActionRequest;
import com.edsp.core.dto.SchemaScanFinishRequest;
import com.edsp.core.dto.SchemaScanRunRequest;
import com.edsp.core.service.JdbcMetadataScanService.MetadataField;
import com.edsp.core.service.JdbcMetadataScanService.MetadataScanResult;
import com.edsp.core.service.JdbcMetadataScanService.MetadataTable;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchemaScanService {
    private static final Pattern ALERT_TABLE_PATTERN =
        Pattern.compile(".*(alert|alarm|event|risk|incident|warning|log).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIT_TABLE_PATTERN =
        Pattern.compile(".*(audit|login|access|operation|download|export).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_FIELD_PATTERN =
        Pattern.compile(".*(time|date|occur|created|updated|timestamp).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_FIELD_PATTERN =
        Pattern.compile("(^id$|.*(_id|uuid|guid|event_id|alert_id|external_id|incident_no).*)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;
    private final JdbcMetadataScanService jdbcMetadataScanService;
    private final CoreRequestSupport support;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SchemaScanService(
        JdbcTemplate jdbcTemplate,
        JdbcMetadataScanService jdbcMetadataScanService,
        CoreRequestSupport support,
        ObjectMapper objectMapper,
        TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcMetadataScanService = jdbcMetadataScanService;
        this.support = support;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public List<Map<String, Object>> runs(int limit) {
        return jdbcTemplate.queryForList("""
            select ssr.id, ssr.data_source_id, ds.name as data_source_name,
                   ssr.scan_type, ssr.status, ssr.started_at, ssr.finished_at,
                   ssr.total_databases, ssr.scanned_databases, ssr.failed_databases,
                   ssr.total_tables, ssr.scanned_tables, ssr.failed_tables,
                   ssr.total_fields, ssr.scanned_fields, ssr.limited,
                   ssr.coverage_rate, ssr.error_message
            from schema_scan_runs ssr
            join data_sources ds on ds.id = ssr.data_source_id
            order by ssr.started_at desc
            limit ?
            """, support.safeLimit(limit));
    }

    public Map<String, Object> createRun(SchemaScanRunRequest request) {
        var resultJson = support.jsonOrEmpty(request.resultJson(), "resultJson");
        var id = insertRun(request.dataSourceId(), request.scanType(), request.status(), resultJson);
        return Map.of("id", id);
    }

    public Map<String, Object> execute(SchemaScanExecuteRequest request) {
        var source = sourceRow(request.dataSourceId());
        var runId = insertRun(request.dataSourceId(), "metadata", "running", "{}");
        try {
            var result = jdbcMetadataScanService.scan(
                support.stringOrDefault(source.get("source_type"), ""),
                source.get("config_json"),
                request.database(),
                support.safeLimit(request.tableLimit(), 1000),
                support.safeLimit(request.fieldLimit(), 1000),
                request.includeViews()
            );
            var changes = transactionTemplate.execute(status -> {
                var summary = persistSnapshot(runId, request.dataSourceId(), result);
                finishRun(runId, "success", result, summary, null);
                jdbcTemplate.update(
                    "update data_sources set status = 'active', updated_at = now() where id = ?",
                    request.dataSourceId());
                return summary;
            });
            var response = new LinkedHashMap<String, Object>();
            response.put("id", runId);
            response.put("status", "success");
            response.put("scannedDatabases", 1);
            response.put("totalAvailableTables", result.totalAvailableTables());
            response.put("scannedTables", result.tableCount());
            response.put("scannedFields", result.fieldCount());
            response.put("limited", result.limited());
            response.put("coverageRate", result.coverageRate());
            response.put("changeCount", changes == null ? 0 : changes.total);
            response.put("pendingChangeCount", changes == null ? 0 : changes.pending);
            response.put("autoAcceptedChangeCount", changes == null ? 0 : changes.autoAccepted);
            return response;
        } catch (RuntimeException ex) {
            var errorMessage = ex.getMessage() == null ? "Metadata scan failed" : ex.getMessage();
            transactionTemplate.executeWithoutResult(status -> {
                finishRun(runId, "failed", emptyResult(), new ChangeSummary(), errorMessage);
                jdbcTemplate.update(
                    "update data_sources set status = 'error', updated_at = now() where id = ?",
                    request.dataSourceId());
            });
            return Map.of(
                "id", runId,
                "status", "failed",
                "errorMessage", errorMessage
            );
        }
    }

    @Transactional
    public Map<String, Object> finishRun(long id, SchemaScanFinishRequest request) {
        var resultJson = support.jsonOrEmpty(request.resultJson(), "resultJson");
        jdbcTemplate.update("""
            update schema_scan_runs
            set status = ?, finished_at = now(),
                total_databases = ?, scanned_databases = ?, failed_databases = ?,
                total_tables = ?, scanned_tables = ?, failed_tables = ?,
                total_fields = ?, scanned_fields = ?,
                limited = ?, coverage_rate = ?,
                error_message = ?, result_json = cast(? as jsonb)
            where id = ?
            """, request.status(), request.totalDatabases(), request.scannedDatabases(),
            request.failedDatabases(), request.totalTables(), request.scannedTables(),
            request.failedTables(), request.totalFields(), request.scannedFields(),
            request.limited(), request.coverageRate(), request.errorMessage(), resultJson, id);
        return Map.of("id", id);
    }

    public List<Map<String, Object>> tables(long id) {
        return jdbcTemplate.queryForList("""
            select st.id, st.data_source_id, ds.name as data_source_name,
                   st.schema_name, st.table_name, st.table_type, st.category,
                   st.row_count, st.confirmation_status, st.lifecycle_status,
                   st.source_updated_at, st.last_seen_at, st.source_removed_at,
                   st.created_at, st.updated_at
            from schema_tables st
            join data_sources ds on ds.id = st.data_source_id
            where st.scan_run_id = ?
            order by st.schema_name, st.table_name
            """, id);
    }

    public List<Map<String, Object>> changes(int limit, String status, Long scanRunId) {
        var safeLimit = support.safeLimit(limit);
        var normalizedStatus = support.blankToNull(status);
        if (normalizedStatus != null && scanRunId != null) {
            return jdbcTemplate.queryForList("""
                select sce.id, sce.data_source_id, ds.name as data_source_name,
                       sce.scan_run_id, sce.schema_table_id, sce.schema_field_id,
                       sce.object_type, sce.change_type, sce.object_name,
                       sce.severity, sce.status, sce.reason, sce.created_at, sce.updated_at
                from schema_change_events sce
                join data_sources ds on ds.id = sce.data_source_id
                where sce.status = ? and sce.scan_run_id = ?
                order by sce.created_at desc
                limit ?
                """, normalizedStatus, scanRunId, safeLimit);
        }
        if (normalizedStatus != null) {
            return jdbcTemplate.queryForList("""
                select sce.id, sce.data_source_id, ds.name as data_source_name,
                       sce.scan_run_id, sce.schema_table_id, sce.schema_field_id,
                       sce.object_type, sce.change_type, sce.object_name,
                       sce.severity, sce.status, sce.reason, sce.created_at, sce.updated_at
                from schema_change_events sce
                join data_sources ds on ds.id = sce.data_source_id
                where sce.status = ?
                order by sce.created_at desc
                limit ?
                """, normalizedStatus, safeLimit);
        }
        if (scanRunId != null) {
            return jdbcTemplate.queryForList("""
                select sce.id, sce.data_source_id, ds.name as data_source_name,
                       sce.scan_run_id, sce.schema_table_id, sce.schema_field_id,
                       sce.object_type, sce.change_type, sce.object_name,
                       sce.severity, sce.status, sce.reason, sce.created_at, sce.updated_at
                from schema_change_events sce
                join data_sources ds on ds.id = sce.data_source_id
                where sce.scan_run_id = ?
                order by sce.created_at desc
                limit ?
                """, scanRunId, safeLimit);
        }
        return jdbcTemplate.queryForList("""
            select sce.id, sce.data_source_id, ds.name as data_source_name,
                   sce.scan_run_id, sce.schema_table_id, sce.schema_field_id,
                   sce.object_type, sce.change_type, sce.object_name,
                   sce.severity, sce.status, sce.reason, sce.created_at, sce.updated_at
            from schema_change_events sce
            join data_sources ds on ds.id = sce.data_source_id
            order by sce.created_at desc
            limit ?
            """, safeLimit);
    }

    @Transactional
    public Map<String, Object> updateChangeStatus(long id, SchemaChangeActionRequest request) {
        var targetStatus = targetChangeStatus(request.action());
        var row = changeRow(id);
        var previousStatus = support.stringOrDefault(row.get("status"), "");
        if (!previousStatus.equals(targetStatus)) {
            jdbcTemplate.update("""
                update schema_change_events
                set status = ?, updated_at = now()
                where id = ?
                """, targetStatus, id);
        }
        auditChangeAction(row, previousStatus, targetStatus, request);
        return changeRow(id);
    }

    @Transactional
    public Map<String, Object> updateChangeStatusBatch(SchemaChangeActionRequest request) {
        if (request.ids().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids must not be empty");
        }
        var updated = 0;
        for (var id : request.ids()) {
            updateChangeStatus(id, request);
            updated++;
        }
        return Map.of(
            "updated", updated,
            "status", targetChangeStatus(request.action())
        );
    }

    private Map<String, Object> sourceRow(long id) {
        var rows = jdbcTemplate.queryForList("""
            select id, source_type, connection_kind, config_json
            from data_sources
            where id = ?
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Data source not found: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> changeRow(long id) {
        var rows = jdbcTemplate.queryForList("""
            select sce.id, sce.data_source_id, ds.name as data_source_name,
                   sce.scan_run_id, sce.schema_table_id, sce.schema_field_id,
                   sce.object_type, sce.change_type, sce.object_name,
                   sce.severity, sce.status, sce.reason, sce.created_at, sce.updated_at
            from schema_change_events sce
            join data_sources ds on ds.id = sce.data_source_id
            where sce.id = ?
            limit 1
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "schema change event not found");
        }
        return rows.get(0);
    }

    private String targetChangeStatus(String action) {
        var value = support.stringOrDefault(action, "").toLowerCase();
        return switch (value) {
            case "accept", "accepted", "confirm", "confirmed" -> "accepted";
            case "ignore", "ignored" -> "ignored";
            case "reopen", "pending" -> "pending";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "action must be accept, ignore, or reopen");
        };
    }

    private void auditChangeAction(
        Map<String, Object> row,
        String previousStatus,
        String targetStatus,
        SchemaChangeActionRequest request
    ) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("previousStatus", previousStatus);
        detail.put("status", targetStatus);
        detail.put("comment", request.comment());
        detail.put("objectType", row.get("object_type"));
        detail.put("changeType", row.get("change_type"));
        detail.put("objectName", row.get("object_name"));
        detail.put("severity", row.get("severity"));
        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """,
            request.operator(),
            "schema_change." + targetStatus,
            "schema_change_event",
            String.valueOf(row.get("id")),
            toJson(detail));
    }

    private Long insertRun(long dataSourceId, String scanType, String status, String resultJson) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into schema_scan_runs(data_source_id, scan_type, status, result_json)
                values (?, ?, ?, cast(? as jsonb))
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, dataSourceId);
            statement.setString(2, scanType);
            statement.setString(3, status);
            statement.setString(4, resultJson);
            return statement;
        }, keyHolder);
        var idValue = keyHolder.getKeys() == null ? null : keyHolder.getKeys().get("id");
        if (idValue instanceof Number number) {
            return number.longValue();
        }
        if (keyHolder.getKey() != null) {
            return keyHolder.getKey().longValue();
        }
        throw new IllegalStateException("Insert did not return a generated id");
    }

    private void finishRun(long runId, String status, MetadataScanResult result, ChangeSummary changes, String errorMessage) {
        jdbcTemplate.update("""
            update schema_scan_runs
            set status = ?, finished_at = now(),
                total_databases = ?, scanned_databases = ?, failed_databases = ?,
                total_tables = ?, scanned_tables = ?, failed_tables = ?,
                total_fields = ?, scanned_fields = ?,
                limited = ?, coverage_rate = ?,
                error_message = ?, result_json = cast(? as jsonb)
            where id = ?
            """,
            status,
            1,
            "success".equals(status) ? 1 : 0,
            "success".equals(status) ? 0 : 1,
            result.totalAvailableTables(),
            "success".equals(status) ? result.tableCount() : 0,
            result.failedTables(),
            result.totalAvailableFields(),
            "success".equals(status) ? result.fieldCount() : 0,
            result.limited(),
            result.coverageRate(),
            errorMessage,
            resultJson(result, status, changes),
            runId);
    }

    private ChangeSummary persistSnapshot(long runId, long dataSourceId, MetadataScanResult result) {
        var changes = new ChangeSummary();
        for (var table : result.tables()) {
            var tableId = upsertTable(runId, dataSourceId, table, changes);
            for (var field : table.fields()) {
                upsertField(runId, dataSourceId, tableId, qualifiedTableName(table), field, changes);
                upsertMapping(tableId, field);
            }
        }
        if (!result.limited()) {
            markMissingObjects(runId, dataSourceId, changes);
        }
        return changes;
    }

    private long upsertTable(long runId, long dataSourceId, MetadataTable table, ChangeSummary changes) {
        var tableName = qualifiedTableName(table);
        var existingRows = jdbcTemplate.queryForList("""
            select id, table_type, lifecycle_status
            from schema_tables
            where data_source_id = ? and table_name = ?
            limit 1
            """, dataSourceId, tableName);
        var category = inferCategory(table.tableName());
        if (!existingRows.isEmpty()) {
            var existing = existingRows.get(0);
            var id = ((Number) existing.get("id")).longValue();
            var previousStatus = support.stringOrDefault(existing.get("lifecycle_status"), "active");
            if ("removed".equals(previousStatus)) {
                recordChange(changes, dataSourceId, runId, id, null, "table", "reappeared", tableName,
                    "low", "auto_accepted",
                    Map.of("lifecycleStatus", previousStatus),
                    tableSnapshot(table, category),
                    "Table was seen again after a previous removal.");
            }
            jdbcTemplate.update("""
                update schema_tables
                set scan_run_id = ?, schema_name = ?, table_type = ?, category = ?,
                    confirmation_status = 'confirmed', lifecycle_status = 'active',
                    row_count = ?, source_updated_at = ?, last_seen_at = now(),
                    source_removed_at = null, updated_at = now()
                where id = ?
                """, runId, table.schemaName(), table.tableType(), category,
                table.rowCount(), table.sourceUpdatedAt(), id);
            return id;
        }

        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into schema_tables(
                    data_source_id, scan_run_id, schema_name, table_name, table_type,
                    category, confirmation_status, lifecycle_status, row_count, source_updated_at, last_seen_at
                )
                values (?, ?, ?, ?, ?, ?, 'confirmed', 'active', ?, ?, now())
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, dataSourceId);
            statement.setLong(2, runId);
            statement.setString(3, table.schemaName());
            statement.setString(4, tableName);
            statement.setString(5, table.tableType());
            statement.setString(6, category);
            if (table.rowCount() == null) {
                statement.setObject(7, null);
            } else {
                statement.setLong(7, table.rowCount());
            }
            statement.setObject(8, table.sourceUpdatedAt());
            return statement;
        }, keyHolder);
        var idValue = keyHolder.getKeys() == null ? null : keyHolder.getKeys().get("id");
        if (idValue instanceof Number number) {
            var id = number.longValue();
            recordChange(changes, dataSourceId, runId, id, null, "table", "added", tableName,
                tableChangeSeverity(category), tableChangeStatus(category), Map.of(), tableSnapshot(table, category),
                "New table discovered during metadata scan.");
            return id;
        }
        if (keyHolder.getKey() == null) {
            throw new IllegalStateException("Insert did not return a generated id");
        }
        var id = keyHolder.getKey().longValue();
        recordChange(changes, dataSourceId, runId, id, null, "table", "added", tableName,
            tableChangeSeverity(category), tableChangeStatus(category), Map.of(), tableSnapshot(table, category),
            "New table discovered during metadata scan.");
        return id;
    }

    private void upsertField(
        long runId,
        long dataSourceId,
        long tableId,
        String tableName,
        MetadataField field,
        ChangeSummary changes
    ) {
        var semanticType = semanticType(field.fieldName(), field.fieldType());
        var existingRows = jdbcTemplate.queryForList("""
            select id, field_type, nullable, lifecycle_status, semantic_type
            from schema_fields
            where schema_table_id = ? and field_name = ?
            limit 1
            """, tableId, field.fieldName());
        if (!existingRows.isEmpty()) {
            var existing = existingRows.get(0);
            var fieldId = ((Number) existing.get("id")).longValue();
            var previousType = support.stringOrDefault(existing.get("field_type"), "");
            var previousNullable = Boolean.TRUE.equals(existing.get("nullable"));
            var previousStatus = support.stringOrDefault(existing.get("lifecycle_status"), "active");
            if ("removed".equals(previousStatus)) {
                recordFieldChange(changes, dataSourceId, runId, tableId, fieldId, tableName, field, semanticType,
                    "reappeared", "low", "auto_accepted", fieldSnapshot(existing), fieldSnapshot(field, semanticType),
                    "Field was seen again after a previous removal.");
            } else if (!previousType.equalsIgnoreCase(field.fieldType())) {
                recordFieldChange(changes, dataSourceId, runId, tableId, fieldId, tableName, field, semanticType,
                    "type_changed", "high", "pending", fieldSnapshot(existing), fieldSnapshot(field, semanticType),
                    "Field type changed and may affect parsing or rule evaluation.");
            } else if (previousNullable != field.nullable()) {
                recordFieldChange(changes, dataSourceId, runId, tableId, fieldId, tableName, field, semanticType,
                    "nullability_changed", "medium", "pending", fieldSnapshot(existing), fieldSnapshot(field, semanticType),
                    "Field nullability changed and may affect required mappings.");
            }
            jdbcTemplate.update("""
                update schema_fields
                set scan_run_id = ?, field_type = ?, nullable = ?, description = ?,
                    ordinal_position = ?, semantic_type = ?, confidence = ?,
                    is_candidate_key = ?, is_time_candidate = ?, lifecycle_status = 'active',
                    last_seen_at = now(), source_removed_at = null
                where id = ?
                """, runId, field.fieldType(), field.nullable(), description(semanticType),
                field.ordinalPosition(), semanticType, confidence(semanticType),
                isCandidateKey(field.fieldName()), isTimeCandidate(field.fieldName(), field.fieldType()),
                fieldId);
            return;
        }

        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
            insert into schema_fields(
                schema_table_id, scan_run_id, field_name, field_type, nullable,
                sample_value, description, ordinal_position, semantic_type, confidence,
                is_candidate_key, is_time_candidate, lifecycle_status, last_seen_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', now())
            """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, tableId);
            statement.setLong(2, runId);
            statement.setString(3, field.fieldName());
            statement.setString(4, field.fieldType());
            statement.setBoolean(5, field.nullable());
            statement.setString(6, null);
            statement.setString(7, description(semanticType));
            statement.setInt(8, field.ordinalPosition());
            statement.setString(9, semanticType);
            statement.setInt(10, confidence(semanticType));
            statement.setBoolean(11, isCandidateKey(field.fieldName()));
            statement.setBoolean(12, isTimeCandidate(field.fieldName(), field.fieldType()));
            return statement;
        }, keyHolder);
        var idValue = keyHolder.getKeys() == null ? null : keyHolder.getKeys().get("id");
        Long fieldId = idValue instanceof Number number
            ? number.longValue()
            : keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        if (fieldId == null) {
            throw new IllegalStateException("Insert did not return a generated id");
        }
        var decision = fieldAddedDecision(semanticType);
        recordFieldChange(changes, dataSourceId, runId, tableId, fieldId, tableName, field, semanticType,
            "added", decision.severity, decision.status, Map.of(), fieldSnapshot(field, semanticType),
            decision.reason);
    }

    private void upsertMapping(long tableId, MetadataField field) {
        var standardField = standardField(field.fieldName());
        if (standardField == null) {
            return;
        }
        var exists = jdbcTemplate.queryForObject("""
            select count(*) from field_mappings
            where schema_table_id = ? and source_field = ? and standard_field = ?
            """, Long.class, tableId, field.fieldName(), standardField);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.update("""
            insert into field_mappings(schema_table_id, source_field, standard_field, transform_rule)
            values (?, ?, ?, ?)
            """, tableId, field.fieldName(), standardField, "auto_recommended");
    }

    private void markMissingObjects(long runId, long dataSourceId, ChangeSummary changes) {
        var missingFields = jdbcTemplate.queryForList("""
            select sf.id, sf.schema_table_id, st.table_name, sf.field_name, sf.field_type,
                   sf.nullable, sf.semantic_type
            from schema_fields sf
            join schema_tables st on st.id = sf.schema_table_id
            where st.data_source_id = ?
              and sf.lifecycle_status = 'active'
              and (sf.scan_run_id is null or sf.scan_run_id <> ?)
            order by st.table_name, sf.field_name
            """, dataSourceId, runId);
        for (var row : missingFields) {
            var tableId = ((Number) row.get("schema_table_id")).longValue();
            var fieldId = ((Number) row.get("id")).longValue();
            var tableName = support.stringOrDefault(row.get("table_name"), "");
            var fieldName = support.stringOrDefault(row.get("field_name"), "");
            var semanticType = support.stringOrDefault(row.get("semantic_type"), semanticType(fieldName,
                support.stringOrDefault(row.get("field_type"), "")));
            recordChange(changes, dataSourceId, runId, tableId, fieldId, "field", "removed",
                tableName + "." + fieldName, "high", "pending", fieldSnapshot(row), Map.of(),
                "Field disappeared from the source metadata and may break collection mappings.");
            jdbcTemplate.update("""
                update schema_fields
                set lifecycle_status = 'removed', source_removed_at = now()
                where id = ?
                """, fieldId);
        }

        var missingTables = jdbcTemplate.queryForList("""
            select id, table_name, table_type, category, lifecycle_status
            from schema_tables
            where data_source_id = ?
              and lifecycle_status = 'active'
              and (scan_run_id is null or scan_run_id <> ?)
            order by table_name
            """, dataSourceId, runId);
        for (var row : missingTables) {
            var tableId = ((Number) row.get("id")).longValue();
            var tableName = support.stringOrDefault(row.get("table_name"), "");
            recordChange(changes, dataSourceId, runId, tableId, null, "table", "removed",
                tableName, "high", "pending", rowSnapshot(row), Map.of(),
                "Table disappeared from the source metadata and may break collection tasks.");
            jdbcTemplate.update("""
                update schema_tables
                set lifecycle_status = 'removed', source_removed_at = now()
                where id = ?
                """, tableId);
        }
    }

    private void recordFieldChange(
        ChangeSummary changes,
        long dataSourceId,
        long runId,
        long tableId,
        long fieldId,
        String tableName,
        MetadataField field,
        String semanticType,
        String changeType,
        String severity,
        String status,
        Map<String, Object> previous,
        Map<String, Object> current,
        String reason
    ) {
        recordChange(changes, dataSourceId, runId, tableId, fieldId, "field", changeType,
            tableName + "." + field.fieldName(), severity, status, previous, current, reason);
    }

    private void recordChange(
        ChangeSummary changes,
        long dataSourceId,
        long runId,
        Long tableId,
        Long fieldId,
        String objectType,
        String changeType,
        String objectName,
        String severity,
        String status,
        Map<String, Object> previous,
        Map<String, Object> current,
        String reason
    ) {
        jdbcTemplate.update("""
            insert into schema_change_events(
                data_source_id, scan_run_id, schema_table_id, schema_field_id,
                object_type, change_type, object_name, severity, status,
                previous_json, current_json, reason
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
            """, dataSourceId, runId, tableId, fieldId, objectType, changeType, objectName,
            severity, status, toJson(previous), toJson(current), reason);
        changes.total++;
        if ("pending".equals(status)) {
            changes.pending++;
        } else if ("auto_accepted".equals(status)) {
            changes.autoAccepted++;
        }
    }

    private String qualifiedTableName(MetadataTable table) {
        var schema = support.blankToNull(table.schemaName());
        if (schema == null) {
            return table.tableName();
        }
        return schema + "." + table.tableName();
    }

    private String inferCategory(String tableName) {
        var value = support.stringOrDefault(tableName, "");
        if (ALERT_TABLE_PATTERN.matcher(value).matches()) {
            return "alert_event";
        }
        if (AUDIT_TABLE_PATTERN.matcher(value).matches()) {
            return "audit_log";
        }
        return "metadata";
    }

    private String semanticType(String fieldName, String fieldType) {
        var name = support.stringOrDefault(fieldName, "").toLowerCase();
        if (isCandidateKey(name)) {
            return "external_id";
        }
        if (isTimeCandidate(name, fieldType)) {
            return "occurred_at";
        }
        if (name.matches(".*(level|severity|risk).*")) {
            return "severity";
        }
        if (name.matches(".*(user|account|operator|actor|employee|sender).*")) {
            return "actor";
        }
        if (name.matches(".*(host|asset|device|ip|terminal).*")) {
            return "asset_ref";
        }
        if (name.matches(".*(title|name|event|alert|incident).*")) {
            return "title";
        }
        if (name.matches(".*(phone|mobile|email|card|cert|address|customer).*")) {
            return "sensitive_value";
        }
        return "plain";
    }

    private String standardField(String fieldName) {
        var semanticType = semanticType(fieldName, "");
        return switch (semanticType) {
            case "external_id" -> "externalId";
            case "occurred_at" -> "occurredAt";
            case "severity" -> "severity";
            case "actor" -> "actor";
            case "asset_ref" -> "assetRef";
            case "title" -> "title";
            case "sensitive_value" -> "detail.sensitiveValue";
            default -> null;
        };
    }

    private boolean isCandidateKey(String fieldName) {
        return KEY_FIELD_PATTERN.matcher(support.stringOrDefault(fieldName, "")).matches();
    }

    private boolean isTimeCandidate(String fieldName, String fieldType) {
        return TIME_FIELD_PATTERN.matcher(support.stringOrDefault(fieldName, "")).matches()
            || support.stringOrDefault(fieldType, "").toLowerCase().contains("time")
            || support.stringOrDefault(fieldType, "").toLowerCase().contains("date");
    }

    private int confidence(String semanticType) {
        return "plain".equals(semanticType) ? 40 : 80;
    }

    private String description(String semanticType) {
        return switch (semanticType) {
            case "external_id" -> "Candidate unique event identifier";
            case "occurred_at" -> "Candidate event time field";
            case "severity" -> "Candidate severity or risk level field";
            case "actor" -> "Candidate actor account field";
            case "asset_ref" -> "Candidate asset or endpoint field";
            case "title" -> "Candidate event title field";
            case "sensitive_value" -> "Candidate sensitive data field";
            default -> "Scanned metadata field";
        };
    }

    private ChangeDecision fieldAddedDecision(String semanticType) {
        return switch (semanticType) {
            case "plain" -> new ChangeDecision("info", "auto_accepted", "Ordinary field added automatically.");
            case "sensitive_value" -> new ChangeDecision("medium", "pending",
                "Sensitive-looking field added; confirm before using it in alert content.");
            default -> new ChangeDecision("low", "pending",
                "Field may affect standard mappings or rule evaluation.");
        };
    }

    private String tableChangeSeverity(String category) {
        return "metadata".equals(category) ? "info" : "low";
    }

    private String tableChangeStatus(String category) {
        return "metadata".equals(category) ? "auto_accepted" : "pending";
    }

    private Map<String, Object> tableSnapshot(MetadataTable table, String category) {
        var data = new LinkedHashMap<String, Object>();
        data.put("databaseName", table.databaseName());
        data.put("schemaName", table.schemaName());
        data.put("tableName", table.tableName());
        data.put("tableType", table.tableType());
        data.put("category", category);
        data.put("rowCount", table.rowCount());
        return data;
    }

    private Map<String, Object> fieldSnapshot(MetadataField field, String semanticType) {
        var data = new LinkedHashMap<String, Object>();
        data.put("fieldName", field.fieldName());
        data.put("fieldType", field.fieldType());
        data.put("nullable", field.nullable());
        data.put("ordinalPosition", field.ordinalPosition());
        data.put("semanticType", semanticType);
        return data;
    }

    private Map<String, Object> fieldSnapshot(Map<String, Object> row) {
        var data = new LinkedHashMap<String, Object>();
        data.put("fieldName", row.get("field_name"));
        data.put("fieldType", row.get("field_type"));
        data.put("nullable", row.get("nullable"));
        data.put("semanticType", row.get("semantic_type"));
        return data;
    }

    private Map<String, Object> rowSnapshot(Map<String, Object> row) {
        var data = new LinkedHashMap<String, Object>();
        row.forEach(data::put);
        return data;
    }

    private MetadataScanResult emptyResult() {
        return new MetadataScanResult("", List.of(), 0);
    }

    private String resultJson(MetadataScanResult result, String status, ChangeSummary changes) {
        var data = new LinkedHashMap<String, Object>();
        data.put("status", status);
        data.put("databaseName", result.databaseName());
        data.put("totalAvailableTables", result.totalAvailableTables());
        data.put("tableCount", result.tableCount());
        data.put("limited", result.limited());
        data.put("coverageRate", result.coverageRate());
        data.put("totalAvailableFields", result.totalAvailableFields());
        data.put("fieldCount", result.fieldCount());
        data.put("changeCount", changes.total);
        data.put("pendingChangeCount", changes.pending);
        data.put("autoAcceptedChangeCount", changes.autoAccepted);
        return toJson(data);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static class ChangeSummary {
        private int total;
        private int pending;
        private int autoAccepted;
    }

    private record ChangeDecision(String severity, String status, String reason) {
    }
}
