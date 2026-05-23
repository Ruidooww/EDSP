package com.edsp.core.service;

import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;

    public AlertRepository(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
    }

    public Map<String, Object> createFromDecision(AlertGenerationService.AlertCandidate candidate) {
        var existing = findByDecisionId(candidate.decisionId());
        if (existing != null) {
            existing.put("action", "existing");
            return existing;
        }

        try {
            var keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                    insert into alerts(
                        title, severity, status, rule_id, subject_type, subject_ref, detail_json,
                        source_system, external_id, alert_type, occurred_at, actor, asset_ref, policy_name,
                        standard_event_id, alert_decision_id
                    )
                    values (?, ?, 'open', ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
                statement.setString(1, candidate.title());
                statement.setString(2, candidate.severity());
                statement.setObject(3, candidate.ruleId());
                statement.setString(4, candidate.subjectType());
                statement.setString(5, candidate.subjectRef());
                statement.setString(6, toJson(candidate.detail()));
                statement.setString(7, candidate.sourceSystem());
                statement.setString(8, candidate.externalId());
                statement.setString(9, candidate.alertType());
                statement.setTimestamp(10, candidate.occurredAt());
                statement.setString(11, candidate.actor());
                statement.setString(12, candidate.assetRef());
                statement.setString(13, candidate.policyName());
                statement.setLong(14, candidate.standardEventId());
                statement.setLong(15, candidate.decisionId());
                return statement;
            }, keyHolder);
        } catch (DataIntegrityViolationException ex) {
            var raced = findByDecisionId(candidate.decisionId());
            if (raced == null) {
                throw ex;
            }
            raced.put("action", "existing");
            return raced;
        }

        var created = findByDecisionId(candidate.decisionId());
        if (created == null) {
            return Map.of("decisionId", candidate.decisionId(), "action", "created");
        }
        created.put("action", "created");
        return created;
    }

    public Map<String, Object> findByDecisionId(long decisionId) {
        var rows = rows("""
            select a.id, a.title, a.severity, a.status, a.rule_id, r.name as rule_name,
                   a.subject_type, a.subject_ref, a.source_system, a.external_id, a.alert_type,
                   a.occurred_at, a.actor, a.asset_ref, a.policy_name, a.standard_event_id,
                   a.alert_decision_id, cast(a.detail_json as varchar) as detail_json,
                   a.created_at, a.updated_at
            from alerts a
            left join rules r on r.id = a.rule_id
            where a.alert_decision_id = ?
            limit 1
            """, decisionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> list(String status, String severity, int limit) {
        var safeLimit = support.safeLimit(limit, 200);
        var safeStatus = support.stringOrNull(status);
        var safeSeverity = support.stringOrNull(severity);
        if (safeStatus != null && safeSeverity != null) {
            return rows("""
                select a.id, a.title, a.severity, a.status, a.rule_id, r.name as rule_name,
                       a.subject_type, a.subject_ref, a.source_system, a.external_id, a.alert_type,
                       a.occurred_at, a.actor, a.asset_ref, a.policy_name, a.standard_event_id,
                       a.alert_decision_id, cast(a.detail_json as varchar) as detail_json,
                       a.created_at, a.updated_at
                from alerts a
                left join rules r on r.id = a.rule_id
                where a.status = ? and a.severity = ?
                order by a.created_at desc, a.id desc
                limit ?
                """, safeStatus, safeSeverity, safeLimit);
        }
        if (safeStatus != null) {
            return rows("""
                select a.id, a.title, a.severity, a.status, a.rule_id, r.name as rule_name,
                       a.subject_type, a.subject_ref, a.source_system, a.external_id, a.alert_type,
                       a.occurred_at, a.actor, a.asset_ref, a.policy_name, a.standard_event_id,
                       a.alert_decision_id, cast(a.detail_json as varchar) as detail_json,
                       a.created_at, a.updated_at
                from alerts a
                left join rules r on r.id = a.rule_id
                where a.status = ?
                order by a.created_at desc, a.id desc
                limit ?
                """, safeStatus, safeLimit);
        }
        if (safeSeverity != null) {
            return rows("""
                select a.id, a.title, a.severity, a.status, a.rule_id, r.name as rule_name,
                       a.subject_type, a.subject_ref, a.source_system, a.external_id, a.alert_type,
                       a.occurred_at, a.actor, a.asset_ref, a.policy_name, a.standard_event_id,
                       a.alert_decision_id, cast(a.detail_json as varchar) as detail_json,
                       a.created_at, a.updated_at
                from alerts a
                left join rules r on r.id = a.rule_id
                where a.severity = ?
                order by a.created_at desc, a.id desc
                limit ?
                """, safeSeverity, safeLimit);
        }
        return rows("""
            select a.id, a.title, a.severity, a.status, a.rule_id, r.name as rule_name,
                   a.subject_type, a.subject_ref, a.source_system, a.external_id, a.alert_type,
                   a.occurred_at, a.actor, a.asset_ref, a.policy_name, a.standard_event_id,
                   a.alert_decision_id, cast(a.detail_json as varchar) as detail_json,
                   a.created_at, a.updated_at
            from alerts a
            left join rules r on r.id = a.rule_id
            order by a.created_at desc, a.id desc
            limit ?
            """, safeLimit);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getLong("id"));
            row.put("title", rs.getString("title"));
            row.put("severity", rs.getString("severity"));
            row.put("status", rs.getString("status"));
            row.put("ruleId", rs.getObject("rule_id"));
            row.put("ruleName", rs.getString("rule_name"));
            row.put("subjectType", rs.getString("subject_type"));
            row.put("subjectRef", rs.getString("subject_ref"));
            row.put("sourceSystem", rs.getString("source_system"));
            row.put("externalId", rs.getString("external_id"));
            row.put("alertType", rs.getString("alert_type"));
            row.put("occurredAt", rs.getObject("occurred_at"));
            row.put("actor", rs.getString("actor"));
            row.put("assetRef", rs.getString("asset_ref"));
            row.put("policyName", rs.getString("policy_name"));
            row.put("standardEventId", rs.getObject("standard_event_id"));
            row.put("decisionId", rs.getObject("alert_decision_id"));
            row.put("detail", parseJson(rs.getObject("detail_json")));
            row.put("createdAt", rs.getObject("created_at"));
            row.put("updatedAt", rs.getObject("updated_at"));
            return row;
        }, args);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize alert detail", ex);
        }
    }
}
