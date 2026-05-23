package com.edsp.core.service;

import com.edsp.core.service.RuleEvaluationService.EvaluationResult;
import com.edsp.core.service.RuleEvaluationService.RuleRecord;
import com.edsp.core.service.RuleEvaluationService.StandardEventContext;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuleDecisionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;

    public RuleDecisionRepository(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
    }

    public Map<String, Object> upsert(
        StandardEventContext event,
        RuleRecord rule,
        EvaluationResult result
    ) {
        var detailJson = toJson(result.detail() == null ? Map.of() : result.detail());
        if (isH2()) {
            jdbcTemplate.update("""
                merge into alert_decisions(
                    standard_event_id, rule_id, decision, severity, risk_score, reason, detail_json
                )
                key(standard_event_id, rule_id)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, event.id(), rule.id(), result.decision(), rule.severity(),
                event.riskScore(), result.reason(), detailJson);
        } else {
            jdbcTemplate.update("""
            insert into alert_decisions(
                standard_event_id, rule_id, decision, severity, risk_score, reason, detail_json
            )
            values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
            on conflict (standard_event_id, rule_id) do update
            set decision = excluded.decision,
                severity = excluded.severity,
                risk_score = excluded.risk_score,
                reason = excluded.reason,
                detail_json = excluded.detail_json
            """, event.id(), rule.id(), result.decision(), rule.severity(),
                event.riskScore(), result.reason(), detailJson);
        }
        return get(event.id(), rule.id());
    }

    public List<Map<String, Object>> list(Long standardEventId, String decision, int limit) {
        var safeLimit = support.safeLimit(limit, 200);
        if (standardEventId != null && support.stringOrNull(decision) != null) {
            return rows("""
                select ad.id, ad.standard_event_id, ad.rule_id, r.name as rule_name,
                       ad.decision, ad.severity, ad.risk_score, ad.reason, ad.detail_json, ad.created_at
                from alert_decisions ad
                left join rules r on r.id = ad.rule_id
                where ad.standard_event_id = ? and ad.decision = ?
                order by ad.created_at desc, ad.id desc
                limit ?
                """, standardEventId, decision, safeLimit);
        }
        if (standardEventId != null) {
            return rows("""
                select ad.id, ad.standard_event_id, ad.rule_id, r.name as rule_name,
                       ad.decision, ad.severity, ad.risk_score, ad.reason, ad.detail_json, ad.created_at
                from alert_decisions ad
                left join rules r on r.id = ad.rule_id
                where ad.standard_event_id = ?
                order by ad.created_at desc, ad.id desc
                limit ?
                """, standardEventId, safeLimit);
        }
        if (support.stringOrNull(decision) != null) {
            return rows("""
                select ad.id, ad.standard_event_id, ad.rule_id, r.name as rule_name,
                       ad.decision, ad.severity, ad.risk_score, ad.reason, ad.detail_json, ad.created_at
                from alert_decisions ad
                left join rules r on r.id = ad.rule_id
                where ad.decision = ?
                order by ad.created_at desc, ad.id desc
                limit ?
                """, decision, safeLimit);
        }
        return rows("""
            select ad.id, ad.standard_event_id, ad.rule_id, r.name as rule_name,
                   ad.decision, ad.severity, ad.risk_score, ad.reason, ad.detail_json, ad.created_at
            from alert_decisions ad
            left join rules r on r.id = ad.rule_id
            order by ad.created_at desc, ad.id desc
            limit ?
            """, safeLimit);
    }

    private Map<String, Object> get(Long standardEventId, Long ruleId) {
        var rows = rows("""
            select ad.id, ad.standard_event_id, ad.rule_id, r.name as rule_name,
                   ad.decision, ad.severity, ad.risk_score, ad.reason, ad.detail_json, ad.created_at
            from alert_decisions ad
            left join rules r on r.id = ad.rule_id
            where ad.standard_event_id = ? and ad.rule_id = ?
            limit 1
            """, standardEventId, ruleId);
        if (rows.isEmpty()) {
            return Map.of(
                "standardEventId", standardEventId,
                "ruleId", ruleId
            );
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getLong("id"));
            row.put("standardEventId", rs.getLong("standard_event_id"));
            row.put("ruleId", rs.getLong("rule_id"));
            row.put("ruleName", rs.getString("rule_name"));
            row.put("decision", rs.getString("decision"));
            row.put("severity", rs.getString("severity"));
            row.put("riskScore", rs.getObject("risk_score"));
            row.put("reason", rs.getString("reason"));
            row.put("detail", parseJson(rs.getObject("detail_json")));
            row.put("createdAt", rs.getObject("created_at"));
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
            throw new IllegalStateException("Unable to serialize rule decision detail", ex);
        }
    }

    private boolean isH2() {
        var dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return false;
        }
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
        } catch (SQLException ex) {
            return false;
        }
    }
}
