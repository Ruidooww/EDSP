package com.edsp.core.service;

import com.edsp.core.dto.RuleEvaluationRunRequest;
import com.edsp.core.service.RuleEvaluationService.RuleRecord;
import com.edsp.core.service.RuleEvaluationService.StandardEventContext;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuleDecisionRunner {
    private final JdbcTemplate jdbcTemplate;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;
    private final RuleDecisionRepository repository;
    private final RuleEvaluationService evaluator;

    public RuleDecisionRunner(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support,
        RuleDecisionRepository repository,
        RuleEvaluationService evaluator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
        this.repository = repository;
        this.evaluator = evaluator;
    }

    @Transactional
    public Map<String, Object> run(RuleEvaluationRunRequest request) {
        if (request == null || request.standardEventId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "standardEventId is required");
        }
        var event = loadStandardEvent(request.standardEventId());
        var rules = request.ruleId() == null
            ? enabledRules()
            : List.of(enabledRule(request.ruleId()));

        var decisions = new ArrayList<Map<String, Object>>();
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("matched", 0);
        counts.put("not_matched", 0);
        counts.put("skipped", 0);
        counts.put("error", 0);

        for (var rule : rules) {
            var result = evaluator.evaluate(event, rule);
            decisions.add(repository.upsert(event, rule, result));
            counts.put(result.decision(), counts.getOrDefault(result.decision(), 0) + 1);
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("standardEventId", event.id());
        if (request.ruleId() != null) {
            response.put("ruleId", request.ruleId());
        }
        response.put("evaluatedCount", rules.size());
        response.put("matchedCount", counts.get("matched"));
        response.put("notMatchedCount", counts.get("not_matched"));
        response.put("skippedCount", counts.get("skipped"));
        response.put("errorCount", counts.get("error"));
        response.put("decisions", decisions);
        return response;
    }

    public List<Map<String, Object>> list(Long standardEventId, String decision, int limit) {
        return repository.list(standardEventId, support.stringOrNull(decision), limit);
    }

    private StandardEventContext loadStandardEvent(long standardEventId) {
        var rows = jdbcTemplate.queryForList("""
            select id, event_type, actor, asset_ref, subject_ref, action, result, severity,
                   risk_score, normalized_json, extra_json
            from standard_events
            where id = ?
            """, standardEventId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "standardEventId not found");
        }
        var row = rows.get(0);
        return new StandardEventContext(
            support.number(row.get("id")),
            support.stringOrNull(row.get("event_type")),
            support.stringOrNull(row.get("actor")),
            support.stringOrNull(row.get("asset_ref")),
            support.stringOrNull(row.get("subject_ref")),
            support.stringOrNull(row.get("action")),
            support.stringOrNull(row.get("result")),
            support.stringOrNull(row.get("severity")),
            intOrNull(row.get("risk_score")),
            row.get("normalized_json"),
            row.get("extra_json")
        );
    }

    private List<RuleRecord> enabledRules() {
        return jdbcTemplate.query("""
            select id, name, event_type, severity, expression, enabled
            from rules
            where enabled = true
            order by id
            """, (rs, rowNum) -> new RuleRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("event_type"),
                rs.getString("severity"),
                rs.getString("expression"),
                rs.getBoolean("enabled")
            ));
    }

    private RuleRecord enabledRule(long ruleId) {
        var rules = jdbcTemplate.query("""
            select id, name, event_type, severity, expression, enabled
            from rules
            where id = ?
            """, (rs, rowNum) -> new RuleRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("event_type"),
                rs.getString("severity"),
                rs.getString("expression"),
                rs.getBoolean("enabled")
            ), ruleId);
        if (rules.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ruleId not found");
        }
        var rule = rules.get(0);
        if (!Boolean.TRUE.equals(rule.enabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ruleId must reference an enabled rule");
        }
        return rule;
    }

    private Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        var text = support.stringOrNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
