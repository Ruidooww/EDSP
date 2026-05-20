package com.edsp.alert.service;

import com.edsp.alert.dto.IngestAlertRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

@Service
public class AlertIngestService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RuleExecutionService ruleExecutionService;

    public AlertIngestService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        RuleExecutionService ruleExecutionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ruleExecutionService = ruleExecutionService;
    }

    public Map<String, Object> ingest(IngestAlertRequest request) {
        var sourceSystem = normalizeRequired(request.sourceSystem());
        var externalId = normalizeOptional(request.externalId());
        var existingId = findExisting(sourceSystem, externalId);
        var occurredAt = parseOccurredAt(request.occurredAt());
        var detailJson = detailJson(request);
        var severity = normalizeSeverity(request.severity());
        var status = normalizeStatus(request.status());

        if (existingId != null) {
            jdbcTemplate.update("""
                update alerts
                set title = ?, severity = ?, status = ?, alert_type = ?, occurred_at = ?,
                    actor = ?, asset_ref = ?, policy_name = ?, subject_type = ?, subject_ref = ?,
                    detail_json = cast(? as jsonb), updated_at = now()
                where id = ?
                """,
                request.title(), severity, status, request.alertType(), occurredAt,
                request.actor(), request.asset(), request.policyName(), request.subjectType(), request.subjectRef(),
                detailJson, existingId);
            saveAudit("alert.ingest.updated", existingId, request, sourceSystem, externalId);
            return ingestResult(existingId, "updated", sourceSystem, externalId, Map.of("matched", 0, "rules", List.of(), "notifications", List.of()));
        }

        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into alerts(
                    title, severity, status, subject_type, subject_ref, detail_json,
                    source_system, external_id, alert_type, occurred_at, actor, asset_ref, policy_name
                )
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?)
                """, new String[] {"id"});
            statement.setString(1, request.title());
            statement.setString(2, severity);
            statement.setString(3, status);
            statement.setString(4, request.subjectType());
            statement.setString(5, request.subjectRef());
            statement.setString(6, detailJson);
            statement.setString(7, sourceSystem);
            statement.setString(8, externalId);
            statement.setString(9, request.alertType());
            statement.setTimestamp(10, occurredAt);
            statement.setString(11, request.actor());
            statement.setString(12, request.asset());
            statement.setString(13, request.policyName());
            return statement;
        }, keyHolder);

        var idValue = keyHolder.getKey();
        var id = idValue == null ? 0 : idValue.longValue();
        saveAudit("alert.ingest.created", id, request, sourceSystem, externalId);
        var ruleResult = ruleExecutionService.execute(id, request, severity, occurredAt);
        return ingestResult(id, "created", sourceSystem, externalId, ruleResult);
    }

    public Map<String, Object> ingestBatch(List<IngestAlertRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Map.of("created", 0, "updated", 0, "total", 0);
        }
        var created = 0;
        var updated = 0;
        for (var request : requests) {
            var result = ingest(request);
            if ("updated".equals(result.get("action"))) {
                updated++;
            } else {
                created++;
            }
        }
        return Map.of("created", created, "updated", updated, "total", requests.size());
    }

    public Map<String, Object> updateStatus(long id, String status) {
        var normalizedStatus = normalizeStatus(status);
        jdbcTemplate.update("update alerts set status = ?, updated_at = now() where id = ?", normalizedStatus, id);

        var detail = new LinkedHashMap<String, Object>();
        detail.put("id", id);
        detail.put("status", normalizedStatus);
        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, "admin", "alert.status.updated", "alert", String.valueOf(id), toJson(detail));
        return Map.of("id", id, "status", normalizedStatus);
    }

    private Long findExisting(String sourceSystem, String externalId) {
        if (externalId == null) {
            return null;
        }
        var ids = jdbcTemplate.queryForList("""
            select id
            from alerts
            where source_system = ? and external_id = ?
            limit 1
            """, Long.class, sourceSystem, externalId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Timestamp parseOccurredAt(String value) {
        if (value == null || value.isBlank()) {
            return Timestamp.from(Instant.now());
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
            try {
                return Timestamp.from(Instant.parse(value));
            } catch (DateTimeParseException ignoredAgain) {
                return Timestamp.from(Instant.now());
            }
        }
    }

    private String detailJson(IngestAlertRequest request) {
        var detail = new LinkedHashMap<String, Object>();
        if (request.detail() != null) {
            detail.putAll(request.detail());
        }
        detail.putIfAbsent("sourceSystem", request.sourceSystem());
        detail.putIfAbsent("externalId", request.externalId());
        detail.putIfAbsent("alertType", request.alertType());
        detail.putIfAbsent("actor", request.actor());
        detail.putIfAbsent("asset", request.asset());
        detail.putIfAbsent("policyName", request.policyName());
        return toJson(detail);
    }

    private Map<String, Object> ingestResult(
        long id,
        String action,
        String sourceSystem,
        String externalId,
        Map<String, Object> ruleResult
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("action", action);
        result.put("sourceSystem", sourceSystem);
        result.put("ruleExecution", ruleResult);
        if (externalId != null) {
            result.put("externalId", externalId);
        }
        return result;
    }

    private void saveAudit(String action, long alertId, IngestAlertRequest request, String sourceSystem, String externalId) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("id", alertId);
        detail.put("sourceSystem", sourceSystem);
        detail.put("externalId", externalId);
        detail.put("alertType", request.alertType());
        detail.put("title", request.title());
        detail.put("severity", normalizeSeverity(request.severity()));
        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, "system", action, "alert", String.valueOf(alertId), toJson(detail));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String normalizeRequired(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeSeverity(String value) {
        if (value == null || value.isBlank()) {
            return "medium";
        }
        return switch (value.trim().toLowerCase()) {
            case "critical", "high", "medium", "low", "info" -> value.trim().toLowerCase();
            case "高危", "严重" -> "high";
            case "中危", "中等" -> "medium";
            case "低危" -> "low";
            case "提示", "信息" -> "info";
            default -> value.trim().toLowerCase();
        };
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return "open";
        }
        return switch (value.trim().toLowerCase()) {
            case "open", "processing", "resolved", "closed" -> value.trim().toLowerCase();
            case "未处理" -> "open";
            case "处理中" -> "processing";
            case "已确认", "已恢复" -> "resolved";
            case "已关闭", "关闭" -> "closed";
            default -> value.trim().toLowerCase();
        };
    }
}
