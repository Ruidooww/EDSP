package com.edsp.core.service;

import com.edsp.core.dto.IngestionPlanActivationRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionPlanActivationService {
    private static final Set<String> ACTIVATABLE_PLAN_STATUSES = Set.of("approved", "shadow_ready");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;
    private final PlanFingerprintSupport planFingerprintSupport;

    public IngestionPlanActivationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support,
        PlanFingerprintSupport planFingerprintSupport
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
        this.planFingerprintSupport = planFingerprintSupport;
    }

    @Transactional
    public Map<String, Object> activate(long planId, IngestionPlanActivationRequest request) {
        var plan = loadPlan(planId);
        var planStatus = support.stringOrDefault(plan.get("status"), "");
        if (!ACTIVATABLE_PLAN_STATUSES.contains(planStatus)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Ingestion plan must be approved or shadow_ready before activation: " + planStatus
            );
        }
        if (request == null || request.shadowRunId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shadowRunId is required");
        }
        ensureNoActiveActivation(planId);

        var shadowRun = loadShadowRun(request.shadowRunId());
        var shadowRunPlanId = support.number(shadowRun.get("ingestion_plan_id"));
        if (shadowRunPlanId == null || shadowRunPlanId != planId) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Shadow Run does not belong to ingestion plan: " + request.shadowRunId()
            );
        }

        var latestRun = latestShadowRun(planId);
        var latestRunId = support.number(latestRun.get("id"));
        if (latestRunId == null || latestRunId.longValue() != request.shadowRunId()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only the latest Shadow Run can activate an ingestion plan"
            );
        }
        var latestStatus = support.stringOrDefault(latestRun.get("status"), "");
        if (!"passed".equals(latestStatus)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Latest Shadow Run must be passed before activation: " + latestStatus
            );
        }

        var planFingerprint = planFingerprintSupport.fingerprint(plan.get("plan_json"));
        validatePlanFingerprint(latestRun, planFingerprint);
        var planJson = parseJson(plan.get("plan_json"));
        var config = activationConfig(planStatus, request.shadowRunId(), planJson, planFingerprint);
        var activationId = insertActivation(
            planId,
            support.number(plan.get("data_source_id")),
            request.shadowRunId(),
            support.stringOrNull(request.operatorName()),
            support.stringOrNull(request.reason()),
            config
        );
        return activationRow(activationId);
    }

    public List<Map<String, Object>> list(long planId, int limit) {
        ensurePlanExists(planId);
        return jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, shadow_run_id, status,
                   activated_by, activated_at, activation_reason,
                   deactivated_by, deactivated_at, deactivation_reason,
                   config_json, created_at, updated_at
            from ingestion_plan_activations
            where ingestion_plan_id = ?
            order by created_at desc, id desc
            limit ?
            """, planId, support.safeLimit(limit, 50)).stream()
            .map(this::activationRow)
            .toList();
    }

    @Transactional
    public Map<String, Object> deactivate(long activationId, IngestionPlanActivationRequest request) {
        var activation = loadActivation(activationId);
        var status = support.stringOrDefault(activation.get("status"), "");
        if (!"active".equals(status)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only active activation can be deactivated: " + status
            );
        }
        jdbcTemplate.update("""
            update ingestion_plan_activations
            set status = 'deactivated',
                deactivated_by = ?,
                deactivated_at = now(),
                deactivation_reason = ?,
                updated_at = now()
            where id = ?
            """,
            request == null ? null : support.stringOrNull(request.operatorName()),
            request == null ? null : support.stringOrNull(request.reason()),
            activationId
        );
        return activationRow(activationId);
    }

    private Map<String, Object> activationConfig(
        String planStatus,
        Long shadowRunId,
        Map<String, Object> planJson,
        PlanFingerprintSupport.PlanFingerprint planFingerprint
    ) {
        var config = new LinkedHashMap<String, Object>();
        config.put("activationGate", "latest_shadow_run_passed");
        config.put("shadowRunId", shadowRunId);
        config.put("planStatus", planStatus);
        config.put("planFingerprint", planFingerprint.asMap());
        config.put("dedupStrategy", planJson.getOrDefault("dedupStrategy", Map.of()));
        config.put("cursorField", cursorField(planJson));
        config.put("batchSize", 100);
        config.put("errorPolicy", "record_failed_rows");
        config.put("note", "activation record only; no data sync or alert generation");
        return config;
    }

    private Object cursorField(Map<String, Object> planJson) {
        var cursorField = planJson.get("cursorField");
        if (cursorField != null) {
            return cursorField;
        }
        if (planJson.get("syncStrategy") instanceof Map<?, ?> syncStrategy) {
            return syncStrategy.get("cursorField");
        }
        return null;
    }

    private void validatePlanFingerprint(
        Map<String, Object> shadowRun,
        PlanFingerprintSupport.PlanFingerprint currentFingerprint
    ) {
        var report = parseJson(shadowRun.get("report_json"));
        if (!(report.get("planFingerprint") instanceof Map<?, ?> rawFingerprint)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shadow_run_plan_fingerprint_missing");
        }
        Map<String, Object> fingerprint = objectMapper.convertValue(rawFingerprint, new TypeReference<>() {});
        var algorithm = support.stringOrNull(fingerprint.get("algorithm"));
        if (!PlanFingerprintSupport.ALGORITHM.equals(algorithm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shadow_run_plan_fingerprint_invalid");
        }
        var hash = support.stringOrNull(fingerprint.get("hash"));
        if (hash == null || hash.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shadow_run_plan_fingerprint_missing");
        }
        if (!currentFingerprint.hash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shadow_run_stale_after_plan_edit");
        }
    }

    private Long insertActivation(
        long planId,
        Long dataSourceId,
        Long shadowRunId,
        String activatedBy,
        String activationReason,
        Map<String, Object> config
    ) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into ingestion_plan_activations(
                    ingestion_plan_id, data_source_id, shadow_run_id, status,
                    activated_by, activation_reason, config_json
                )
                values (?, ?, ?, 'active', ?, ?, cast(? as jsonb))
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, planId);
            statement.setObject(2, dataSourceId);
            statement.setObject(3, shadowRunId);
            statement.setString(4, activatedBy);
            statement.setString(5, activationReason);
            statement.setString(6, toJson(config));
            return statement;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    private Map<String, Object> loadPlan(long planId) {
        var rows = jdbcTemplate.queryForList("""
            select id, data_source_id, scan_run_id, name, status, plan_json, created_at, updated_at
            from ingestion_plans
            where id = ?
            for update
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

    private void ensureNoActiveActivation(long planId) {
        var count = jdbcTemplate.queryForObject("""
            select count(*)
            from ingestion_plan_activations
            where ingestion_plan_id = ? and status = 'active'
            """, Long.class, planId);
        if (count != null && count > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Ingestion plan already has an active activation: " + planId
            );
        }
    }

    private Map<String, Object> loadShadowRun(long shadowRunId) {
        var rows = jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, status, report_json, created_at
            from ingestion_plan_shadow_runs
            where id = ?
            """, shadowRunId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shadow Run not found: " + shadowRunId);
        }
        return rows.get(0);
    }

    private Map<String, Object> latestShadowRun(long planId) {
        var rows = jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, status, report_json, created_at
            from ingestion_plan_shadow_runs
            where ingestion_plan_id = ?
            order by created_at desc, id desc
            limit 1
            """, planId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingestion plan has no Shadow Run: " + planId);
        }
        return rows.get(0);
    }

    private Map<String, Object> loadActivation(long activationId) {
        var rows = jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, shadow_run_id, status,
                   activated_by, activated_at, activation_reason,
                   deactivated_by, deactivated_at, deactivation_reason,
                   config_json, created_at, updated_at
            from ingestion_plan_activations
            where id = ?
            """, activationId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Ingestion plan activation not found: " + activationId
            );
        }
        return rows.get(0);
    }

    private Map<String, Object> activationRow(Long activationId) {
        return activationRow(loadActivation(activationId));
    }

    private Map<String, Object> activationRow(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id"));
        result.put("ingestionPlanId", row.get("ingestion_plan_id"));
        result.put("dataSourceId", row.get("data_source_id"));
        result.put("shadowRunId", row.get("shadow_run_id"));
        result.put("status", row.get("status"));
        result.put("activatedBy", row.get("activated_by"));
        result.put("activatedAt", row.get("activated_at"));
        result.put("activationReason", row.get("activation_reason"));
        result.put("deactivatedBy", row.get("deactivated_by"));
        result.put("deactivatedAt", row.get("deactivated_at"));
        result.put("deactivationReason", row.get("deactivation_reason"));
        result.put("config", parseJson(row.get("config_json")));
        result.put("createdAt", row.get("created_at"));
        result.put("updatedAt", row.get("updated_at"));
        return result;
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
            throw new IllegalStateException("Unable to serialize ingestion plan activation config", ex);
        }
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
}
