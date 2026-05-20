package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.RawEventRequest;
import com.edsp.core.dto.StandardEventRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ingestion")
public class IngestionController {
    private final JdbcTemplate jdbcTemplate;

    public IngestionController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/raw-events")
    public ApiResponse<List<Map<String, Object>>> rawEvents(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select re.id, re.data_source_id, ds.name as data_source_name, re.task_id, re.run_id,
                   re.source_system, re.external_id, re.event_type, re.occurred_at,
                   re.received_at, re.status, re.standard_event_id
            from raw_events re
            left join data_sources ds on ds.id = re.data_source_id
            order by re.received_at desc
            limit ?
            """, limit));
    }

    @PostMapping("/raw-events")
    public ApiResponse<Map<String, Object>> createRawEvent(@Valid @RequestBody RawEventRequest request) {
        var id = jdbcTemplate.queryForObject("""
            insert into raw_events(
                data_source_id, task_id, run_id, source_system, external_id, event_type,
                occurred_at, payload_json, payload_hash, status
            )
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
            returning id
            """, Long.class, request.dataSourceId(), request.taskId(), request.runId(),
            request.sourceSystem(), request.externalId(), request.eventType(),
            parseTime(request.occurredAt()), request.payloadJson(), request.payloadHash(), request.status());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @GetMapping("/standard-events")
    public ApiResponse<List<Map<String, Object>>> standardEvents(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select se.id, se.data_source_id, ds.name as data_source_name,
                   se.source_system, se.external_id, se.event_type, se.occurred_at,
                   se.actor, se.asset_ref, se.subject_type, se.subject_ref,
                   se.action, se.result, se.severity, se.risk_score,
                   se.created_at, se.updated_at
            from standard_events se
            left join data_sources ds on ds.id = se.data_source_id
            order by coalesce(se.occurred_at, se.created_at) desc
            limit ?
            """, limit));
    }

    @PostMapping("/standard-events")
    public ApiResponse<Map<String, Object>> createStandardEvent(
        @Valid @RequestBody StandardEventRequest request
    ) {
        var existingId = existingStandardEventId(request.sourceSystem(), request.externalId());
        if (existingId != null) {
            updateStandardEvent(existingId, request);
            linkRawEvent(request.rawEventId(), existingId);
            return ApiResponse.ok(Map.of("id", existingId), "updated");
        }
        var id = jdbcTemplate.queryForObject("""
            insert into standard_events(
                raw_event_id, raw_log_id, raw_import_id, data_source_id,
                source_system, external_id, event_type, occurred_at,
                actor, asset_ref, subject_type, subject_ref, action, result,
                severity, risk_score, normalized_json, extra_json
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
            returning id
            """, Long.class, request.rawEventId(), request.rawLogId(), request.rawImportId(),
            request.dataSourceId(), request.sourceSystem(), request.externalId(), request.eventType(),
            parseTime(request.occurredAt()), request.actor(), request.assetRef(), request.subjectType(),
            request.subjectRef(), request.action(), request.result(), request.severity(), request.riskScore(),
            request.normalizedJson(), request.extraJson());
        linkRawEvent(request.rawEventId(), id);
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @PostMapping("/standard-events/from-raw/{rawEventId}")
    public ApiResponse<Map<String, Object>> standardizeRawEvent(
        @PathVariable long rawEventId,
        @RequestBody StandardEventRequest request
    ) {
        var rawRows = jdbcTemplate.queryForList("""
            select data_source_id, source_system, external_id, event_type, occurred_at
            from raw_events
            where id = ?
            """, rawEventId);
        if (rawRows.isEmpty()) {
            return ApiResponse.fail("raw event not found");
        }
        var raw = rawRows.get(0);
        var merged = new StandardEventRequest(
            rawEventId,
            request.rawLogId(),
            request.rawImportId(),
            request.dataSourceId() == null ? number(raw.get("data_source_id")) : request.dataSourceId(),
            request.sourceSystem() == null || request.sourceSystem().isBlank()
                ? String.valueOf(raw.get("source_system"))
                : request.sourceSystem(),
            request.externalId() == null || request.externalId().isBlank()
                ? stringOrNull(raw.get("external_id"))
                : request.externalId(),
            request.eventType() == null || request.eventType().isBlank()
                ? String.valueOf(raw.get("event_type"))
                : request.eventType(),
            request.occurredAt(),
            request.actor(),
            request.assetRef(),
            request.subjectType(),
            request.subjectRef(),
            request.action(),
            request.result(),
            request.severity(),
            request.riskScore(),
            request.normalizedJson(),
            request.extraJson()
        );
        return createStandardEvent(merged);
    }

    private void updateStandardEvent(long id, StandardEventRequest request) {
        jdbcTemplate.update("""
            update standard_events
            set raw_event_id = ?, raw_log_id = ?, raw_import_id = ?,
                data_source_id = ?, event_type = ?, occurred_at = ?, actor = ?, asset_ref = ?,
                subject_type = ?, subject_ref = ?, action = ?, result = ?,
                severity = ?, risk_score = ?, normalized_json = cast(? as jsonb),
                extra_json = cast(? as jsonb), updated_at = now()
            where id = ?
            """, request.rawEventId(), request.rawLogId(), request.rawImportId(), request.dataSourceId(),
            request.eventType(), parseTime(request.occurredAt()), request.actor(), request.assetRef(),
            request.subjectType(), request.subjectRef(), request.action(), request.result(),
            request.severity(), request.riskScore(), request.normalizedJson(), request.extraJson(), id);
    }

    private Long existingStandardEventId(String sourceSystem, String externalId) {
        if (sourceSystem == null || sourceSystem.isBlank() || externalId == null || externalId.isBlank()) {
            return null;
        }
        var rows = jdbcTemplate.queryForList("""
            select id
            from standard_events
            where source_system = ? and external_id = ?
            """, sourceSystem, externalId);
        if (rows.isEmpty()) {
            return null;
        }
        return number(rows.get(0).get("id"));
    }

    private void linkRawEvent(Long rawEventId, Long standardEventId) {
        if (rawEventId == null || standardEventId == null) {
            return;
        }
        jdbcTemplate.update("""
            update raw_events
            set status = 'standardized', standard_event_id = ?
            where id = ?
            """, standardEventId, rawEventId);
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
