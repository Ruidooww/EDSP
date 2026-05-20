package com.edsp.alert.controller;

import com.edsp.alert.dto.AlertRequest;
import com.edsp.alert.dto.AlertNoteRequest;
import com.edsp.alert.dto.IngestAlertRequest;
import com.edsp.alert.dto.RuleRequest;
import com.edsp.alert.dto.StatusRequest;
import com.edsp.alert.service.AlertDispositionService;
import com.edsp.alert.service.AlertIngestService;
import com.edsp.common.api.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final JdbcTemplate jdbcTemplate;
    private final AlertIngestService alertIngestService;
    private final AlertDispositionService alertDispositionService;
    private final ObjectMapper objectMapper;

    public AlertController(
        JdbcTemplate jdbcTemplate,
        AlertIngestService alertIngestService,
        AlertDispositionService alertDispositionService,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertIngestService = alertIngestService;
        this.alertDispositionService = alertDispositionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listAlerts() {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, title, severity, status, subject_type, subject_ref,
                   source_system, external_id, alert_type, occurred_at, actor, asset_ref, policy_name,
                   cast(detail_json as varchar) as detail_json,
                   created_at, updated_at
            from alerts
            order by coalesce(occurred_at, created_at) desc
            """));
    }

    @PostMapping("/ingest")
    public ApiResponse<Map<String, Object>> ingestAlert(@Valid @RequestBody IngestAlertRequest request) {
        return ApiResponse.ok(alertIngestService.ingest(request));
    }

    @PostMapping("/ingest/batch")
    public ApiResponse<Map<String, Object>> ingestAlerts(@RequestBody List<@Valid IngestAlertRequest> requests) {
        return ApiResponse.ok(alertIngestService.ingestBatch(requests));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createAlert(@Valid @RequestBody AlertRequest request) {
        return ApiResponse.ok(alertIngestService.ingest(new IngestAlertRequest(
            "manual",
            null,
            "manual",
            request.title(),
            request.severity(),
            null,
            null,
            null,
            null,
            request.subjectType(),
            request.subjectRef(),
            request.status(),
            Map.of("detailJson", request.detailJson())
        )), "created");
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> updateStatus(@PathVariable("id") long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(alertIngestService.updateStatus(id, request.status()), "updated");
    }

    @GetMapping("/{id}/notes")
    public ApiResponse<List<Map<String, Object>>> listNotes(@PathVariable("id") long id) {
        return ApiResponse.ok(alertDispositionService.listNotes(id));
    }

    @PostMapping("/{id}/notes")
    public ApiResponse<Map<String, Object>> addNote(
        @PathVariable("id") long id,
        @Valid @RequestBody AlertNoteRequest request
    ) {
        return ApiResponse.ok(alertDispositionService.addNote(id, request), "created");
    }

    @GetMapping("/rules")
    public ApiResponse<List<Map<String, Object>>> listRules() {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, name, event_type, severity, expression, enabled, created_at, updated_at
            from rules
            order by updated_at desc
            """));
    }

    @PostMapping("/rules")
    public ApiResponse<Map<String, Object>> createRule(@Valid @RequestBody RuleRequest request) {
        var keyHolder = new GeneratedKeyHolder();
        var severity = normalizeSeverity(request.severity());
        var enabled = request.enabled();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
            insert into rules(name, event_type, severity, expression, enabled)
            values (?, ?, ?, ?, ?)
            """, new String[] {"id"});
            statement.setString(1, request.name());
            statement.setString(2, request.eventType());
            statement.setString(3, severity);
            statement.setString(4, request.expression());
            statement.setBoolean(5, enabled);
            return statement;
        }, keyHolder);
        var idValue = keyHolder.getKey();
        var id = idValue == null ? 0 : idValue.longValue();
        saveAudit("rule.created", id, Map.of(
            "id", id,
            "name", request.name(),
            "eventType", request.eventType(),
            "severity", severity,
            "enabled", enabled
        ));
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @PutMapping("/rules/{id}/enabled")
    public ApiResponse<Map<String, Object>> updateRuleEnabled(
        @PathVariable("id") long id,
        @RequestBody Map<String, Boolean> request
    ) {
        var enabled = Boolean.TRUE.equals(request.get("enabled"));
        jdbcTemplate.update("update rules set enabled = ?, updated_at = now() where id = ?", enabled, id);
        saveAudit("rule.enabled.updated", id, Map.of("id", id, "enabled", enabled));
        return ApiResponse.ok(Map.of("id", id, "enabled", enabled), "updated");
    }

    private void saveAudit(String action, long id, Map<String, Object> detail) {
        var payload = new LinkedHashMap<>(detail);
        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, "admin", action, "rule", String.valueOf(id), toJson(payload));
    }

    private String normalizeSeverity(String value) {
        if (value == null || value.isBlank()) {
            return "medium";
        }
        return switch (value.trim().toLowerCase()) {
            case "critical", "high", "medium", "low", "info" -> value.trim().toLowerCase();
            case "严重" -> "critical";
            case "高", "高危" -> "high";
            case "中", "中危" -> "medium";
            case "低", "低危" -> "low";
            case "提示", "信息" -> "info";
            default -> value.trim().toLowerCase();
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
