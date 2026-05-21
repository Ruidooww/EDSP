package com.edsp.core.service;

import com.edsp.core.dto.RawEventRequest;
import com.edsp.core.dto.StandardEventRequest;
import com.edsp.core.support.CoreRequestSupport;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
    private final JdbcTemplate jdbcTemplate;
    private final CoreRequestSupport support;

    public IngestionService(JdbcTemplate jdbcTemplate, CoreRequestSupport support) {
        this.jdbcTemplate = jdbcTemplate;
        this.support = support;
    }

    public List<Map<String, Object>> rawEvents(int limit) {
        return jdbcTemplate.queryForList("""
            select re.id, re.data_source_id, ds.name as data_source_name, re.task_id, re.run_id,
                   re.source_system, re.external_id, re.event_type, re.occurred_at,
                   re.received_at, re.status, re.standard_event_id
            from raw_events re
            left join data_sources ds on ds.id = re.data_source_id
            order by re.received_at desc
            limit ?
            """, support.safeLimit(limit));
    }

    public Map<String, Object> createRawEvent(RawEventRequest request) {
        var payloadJson = support.jsonOrEmpty(request.payloadJson(), "payloadJson");
        var id = insertAndReturnId("""
            insert into raw_events(
                data_source_id, task_id, run_id, source_system, external_id, event_type,
                occurred_at, payload_json, payload_hash, status
            )
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
            """, request.dataSourceId(), request.taskId(), request.runId(),
            support.stringOrDefault(request.sourceSystem(), "external"),
            support.blankToNull(request.externalId()), request.eventType(),
            support.parseTime(request.occurredAt()), payloadJson, request.payloadHash(), request.status());
        return Map.of("id", id);
    }

    public List<Map<String, Object>> standardEvents(int limit) {
        return jdbcTemplate.queryForList("""
            select se.id, se.data_source_id, ds.name as data_source_name,
                   se.source_system, se.external_id, se.event_type, se.occurred_at,
                   se.actor, se.asset_ref, se.subject_type, se.subject_ref,
                   se.action, se.result, se.severity, se.risk_score,
                   se.created_at, se.updated_at
            from standard_events se
            left join data_sources ds on ds.id = se.data_source_id
            order by coalesce(se.occurred_at, se.created_at) desc
            limit ?
            """, support.safeLimit(limit));
    }

    @Transactional
    public Map<String, Object> createStandardEvent(StandardEventRequest request) {
        var normalized = normalize(request);
        var existingId = existingStandardEventId(normalized.dedupKey(), normalized.sourceSystem(), normalized.externalId());
        if (existingId != null) {
            updateStandardEvent(existingId, request, normalized);
            linkRawEvent(request.rawEventId(), existingId);
            return Map.of("id", existingId, "result", "updated");
        }

        var id = insertAndReturnId("""
            insert into standard_events(
                raw_event_id, raw_log_id, raw_import_id, data_source_id,
                source_system, external_id, event_type, occurred_at,
                actor, asset_ref, subject_type, subject_ref, action, result,
                severity, risk_score, normalized_json, extra_json, dedup_key
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
            """, request.rawEventId(), request.rawLogId(), request.rawImportId(),
            request.dataSourceId(), normalized.sourceSystem(), normalized.externalId(), normalized.eventType(),
            normalized.occurredAt(), request.actor(), request.assetRef(), request.subjectType(),
            request.subjectRef(), request.action(), request.result(), request.severity(), request.riskScore(),
            normalized.normalizedJson(), normalized.extraJson(), normalized.dedupKey());
        linkRawEvent(request.rawEventId(), id);
        return Map.of("id", id, "result", "created");
    }

    @Transactional
    public Map<String, Object> standardizeRawEvent(long rawEventId, StandardEventRequest request) {
        var rawRows = jdbcTemplate.queryForList("""
            select data_source_id, source_system, external_id, event_type, occurred_at
            from raw_events
            where id = ?
            """, rawEventId);
        if (rawRows.isEmpty()) {
            return null;
        }
        var raw = rawRows.get(0);
        var merged = new StandardEventRequest(
            rawEventId,
            request.rawLogId(),
            request.rawImportId(),
            request.dataSourceId() == null ? support.number(raw.get("data_source_id")) : request.dataSourceId(),
            request.sourceSystem() == null || request.sourceSystem().isBlank()
                ? support.stringOrDefault(raw.get("source_system"), "external")
                : request.sourceSystem(),
            request.externalId() == null || request.externalId().isBlank()
                ? support.stringOrNull(raw.get("external_id"))
                : request.externalId(),
            request.eventType() == null || request.eventType().isBlank()
                ? support.stringOrDefault(raw.get("event_type"), "unknown")
                : request.eventType(),
            request.occurredAt() == null || request.occurredAt().isBlank()
                ? support.stringOrNull(raw.get("occurred_at"))
                : request.occurredAt(),
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

    private NormalizedStandardEvent normalize(StandardEventRequest request) {
        var occurredAt = support.parseTime(request.occurredAt());
        var sourceSystem = support.stringOrDefault(request.sourceSystem(), "external");
        var externalId = support.blankToNull(request.externalId());
        var eventType = support.stringOrDefault(request.eventType(), "unknown");
        var normalizedJson = support.jsonOrEmpty(request.normalizedJson(), "normalizedJson");
        var extraJson = support.jsonOrEmpty(request.extraJson(), "extraJson");
        var dedupKey = support.dedupKey(sourceSystem, externalId, eventType, occurredAt,
            request.actor(), request.assetRef(), request.subjectRef());
        return new NormalizedStandardEvent(sourceSystem, externalId, eventType, occurredAt,
            normalizedJson, extraJson, dedupKey);
    }

    private void updateStandardEvent(long id, StandardEventRequest request, NormalizedStandardEvent normalized) {
        jdbcTemplate.update("""
            update standard_events
            set raw_event_id = ?, raw_log_id = ?, raw_import_id = ?,
                data_source_id = ?, source_system = ?, external_id = ?, event_type = ?,
                occurred_at = ?, actor = ?, asset_ref = ?,
                subject_type = ?, subject_ref = ?, action = ?, result = ?,
                severity = ?, risk_score = ?, normalized_json = cast(? as jsonb),
                extra_json = cast(? as jsonb), dedup_key = ?, updated_at = now()
            where id = ?
            """, request.rawEventId(), request.rawLogId(), request.rawImportId(), request.dataSourceId(),
            normalized.sourceSystem(), normalized.externalId(), normalized.eventType(), normalized.occurredAt(),
            request.actor(), request.assetRef(), request.subjectType(), request.subjectRef(), request.action(),
            request.result(), request.severity(), request.riskScore(), normalized.normalizedJson(),
            normalized.extraJson(), normalized.dedupKey(), id);
    }

    private Long existingStandardEventId(String dedupKey, String sourceSystem, String externalId) {
        var rows = dedupKey == null || dedupKey.isBlank()
            ? List.<Map<String, Object>>of()
            : jdbcTemplate.queryForList("""
                select id
                from standard_events
                where dedup_key = ?
                """, dedupKey);
        if (rows.isEmpty() && externalId != null) {
            rows = jdbcTemplate.queryForList("""
                select id
                from standard_events
                where source_system = ? and external_id = ?
                """, sourceSystem, externalId);
        }
        if (rows.isEmpty()) {
            return null;
        }
        return support.number(rows.get(0).get("id"));
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

    private record NormalizedStandardEvent(
        String sourceSystem,
        String externalId,
        String eventType,
        OffsetDateTime occurredAt,
        String normalizedJson,
        String extraJson,
        String dedupKey
    ) {
    }
}
