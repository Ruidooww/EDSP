package com.edsp.core.service;

import com.edsp.core.dto.AlertGenerationRunRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertGenerationService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;
    private final AlertRepository repository;

    public AlertGenerationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support,
        AlertRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
        this.repository = repository;
    }

    public Map<String, Object> generate(AlertGenerationRunRequest request) {
        if (request == null || request.decisionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decisionId is required");
        }

        var existing = repository.findByDecisionId(request.decisionId());
        if (existing != null) {
            existing.put("action", "existing");
            return existing;
        }

        var decision = loadDecision(request.decisionId());
        if (!"matched".equals(decision.decision())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only matched alert_decisions can create alerts");
        }
        if (decision.standardEventId() == null || support.stringOrNull(decision.sourceSystem()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alert_decision must reference a standard_event");
        }

        return repository.createFromDecision(candidate(decision));
    }

    private AlertDecisionRecord loadDecision(long decisionId) {
        var rows = jdbcTemplate.query("""
            select ad.id as decision_id, ad.standard_event_id, ad.rule_id, ad.decision,
                   ad.severity as decision_severity, ad.risk_score as decision_risk_score,
                   ad.reason as decision_reason, cast(ad.detail_json as varchar) as decision_detail_json,
                   se.source_system, se.event_type, se.occurred_at, se.actor, se.asset_ref,
                   se.subject_type, se.subject_ref, se.severity as event_severity, se.risk_score as event_risk_score,
                   r.name as rule_name
            from alert_decisions ad
            left join standard_events se on se.id = ad.standard_event_id
            left join rules r on r.id = ad.rule_id
            where ad.id = ?
            limit 1
            """, (rs, rowNum) -> new AlertDecisionRecord(
                rs.getLong("decision_id"),
                (Long) rs.getObject("standard_event_id"),
                (Long) rs.getObject("rule_id"),
                rs.getString("decision"),
                rs.getString("decision_severity"),
                (Integer) rs.getObject("decision_risk_score"),
                rs.getString("decision_reason"),
                rs.getString("decision_detail_json"),
                rs.getString("source_system"),
                rs.getString("event_type"),
                rs.getTimestamp("occurred_at"),
                rs.getString("actor"),
                rs.getString("asset_ref"),
                rs.getString("subject_type"),
                rs.getString("subject_ref"),
                rs.getString("event_severity"),
                (Integer) rs.getObject("event_risk_score"),
                rs.getString("rule_name")
            ), decisionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert_decision not found");
        }
        return rows.get(0);
    }

    private AlertCandidate candidate(AlertDecisionRecord decision) {
        var severity = firstNonBlank(decision.decisionSeverity(), decision.eventSeverity(), "medium");
        var ruleName = firstNonBlank(decision.ruleName(), "Rule " + decision.ruleId());
        var eventType = firstNonBlank(decision.eventType(), "event");
        var title = ruleName + " alert";
        var detail = new LinkedHashMap<String, Object>();
        detail.put("decisionId", decision.decisionId());
        detail.put("standardEventId", decision.standardEventId());
        detail.put("ruleId", decision.ruleId());
        detail.put("ruleName", ruleName);
        detail.put("decision", decision.decision());
        detail.put("reason", decision.reason());
        detail.put("riskScore", decision.decisionRiskScore() == null ? decision.eventRiskScore() : decision.decisionRiskScore());
        detail.put("sourceSystem", decision.sourceSystem());
        detail.put("eventType", eventType);
        detail.put("decisionDetail", parseJson(decision.decisionDetailJson()));

        return new AlertCandidate(
            decision.decisionId(),
            decision.standardEventId(),
            decision.ruleId(),
            title,
            severity,
            decision.subjectType(),
            decision.subjectRef(),
            decision.sourceSystem(),
            "rule-decision-" + decision.decisionId(),
            eventType,
            decision.occurredAt(),
            decision.actor(),
            decision.assetRef(),
            ruleName,
            detail
        );
    }

    private Map<String, Object> parseJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            var node = objectMapper.readTree(value);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            var normalized = support.stringOrNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return "";
    }

    private record AlertDecisionRecord(
        Long decisionId,
        Long standardEventId,
        Long ruleId,
        String decision,
        String decisionSeverity,
        Integer decisionRiskScore,
        String reason,
        String decisionDetailJson,
        String sourceSystem,
        String eventType,
        Timestamp occurredAt,
        String actor,
        String assetRef,
        String subjectType,
        String subjectRef,
        String eventSeverity,
        Integer eventRiskScore,
        String ruleName
    ) {
    }

    public record AlertCandidate(
        Long decisionId,
        Long standardEventId,
        Long ruleId,
        String title,
        String severity,
        String subjectType,
        String subjectRef,
        String sourceSystem,
        String externalId,
        String alertType,
        Timestamp occurredAt,
        String actor,
        String assetRef,
        String policyName,
        Map<String, Object> detail
    ) {
    }
}
