package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.RuleEvaluationRunRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RuleDecisionRunnerTest {
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private RuleDecisionRunner runner;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:rule_decision_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
                + "CREATE DOMAIN IF NOT EXISTS JSONB AS JSON"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .clean();
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper();
        var support = new CoreRequestSupport(objectMapper);
        var repository = new RuleDecisionRepository(jdbcTemplate, objectMapper, support);
        var evaluator = new RuleEvaluationService(objectMapper, support);
        runner = new RuleDecisionRunner(jdbcTemplate, objectMapper, support, repository, evaluator);
    }

    @Test
    void runForStandardEventWritesMatchedDecisionAndIsIdempotentWithoutAlertsOrNotifications() {
        var eventId = insertStandardEvent("file_operation", "high", 90, null);
        var ruleId = insertRule("High risk file operation", "file_operation", "high", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","minSeverity":"medium","threshold":{"metric":"riskScore","operator":">=","value":80}}
            """, true);

        var first = runner.run(new RuleEvaluationRunRequest(eventId, null, "ops-user"));
        var second = runner.run(new RuleEvaluationRunRequest(eventId, null, "ops-user"));

        assertEquals(1, intValue(first.get("evaluatedCount")));
        assertEquals(1, intValue(first.get("matchedCount")));
        assertEquals(1, intValue(second.get("evaluatedCount")));
        assertEquals(1L, count("alert_decisions"));
        assertEquals("matched", stringCell("select decision from alert_decisions where standard_event_id = ?", eventId));
        assertEquals("high", stringCell("select severity from alert_decisions where standard_event_id = ?", eventId));
        assertEquals(90, intCell("select risk_score from alert_decisions where standard_event_id = ?", eventId));
        assertEquals(0L, count("alerts"));
        assertEquals(0L, count("notification_deliveries"));
        var detail = objectValue(jdbcTemplate.queryForObject(
            "select detail_json from alert_decisions where standard_event_id = ? and rule_id = ?",
            Object.class,
            eventId,
            ruleId
        ));
        assertEquals("riskScore", detail.get("metric"));
        assertEquals(80, intValue(detail.get("thresholdValue")));
    }

    @Test
    void runRecordsNotMatchedWhenSeverityOrThresholdDoNotMatch() {
        var eventId = insertStandardEvent("file_operation", "low", 40, null);
        insertRule("Min severity", "file_operation", "medium", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","minSeverity":"medium","threshold":{"metric":"riskScore","operator":">=","value":10}}
            """, true);
        insertRule("High threshold", "file_operation", "high", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"riskScore","operator":">=","value":80}}
            """, true);

        var result = runner.run(new RuleEvaluationRunRequest(eventId, null, "ops-user"));
        var decisions = jdbcTemplate.queryForList(
            "select decision, reason from alert_decisions where standard_event_id = ? order by id",
            eventId
        );

        assertEquals(2, intValue(result.get("notMatchedCount")));
        assertEquals(List.of("not_matched", "not_matched"), decisions.stream().map(row -> row.get("decision")).toList());
        assertTrue(decisions.stream().anyMatch(row -> "min_severity_not_met".equals(row.get("reason"))));
        assertTrue(decisions.stream().anyMatch(row -> "threshold_not_matched".equals(row.get("reason"))));
        assertEquals(0L, count("alerts"));
    }

    @Test
    void runRecordsSkippedForEventTypeMetricAndTimeWindowBoundaries() {
        var eventId = insertStandardEvent("account_activity", "high", null, Map.of("riskScore", "abc"));
        insertRule("Wrong type", "file_operation", "medium", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"riskScore","operator":">=","value":1}}
            """, true);
        insertRule("Missing metric", "account_activity", "medium", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"missingMetric","operator":">=","value":1}}
            """, true);
        insertRule("Non numeric metric", "account_activity", "medium", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"riskScore","operator":">=","value":1}}
            """, true);
        insertRule("Unsupported window", "account_activity", "medium", """
            {"version":1,"mode":"structured_config","timeWindow":"last_1h","threshold":{"metric":"eventCount","operator":">=","value":1}}
            """, true);

        var result = runner.run(new RuleEvaluationRunRequest(eventId, null, "ops-user"));
        var reasons = jdbcTemplate.queryForList(
            "select reason from alert_decisions where standard_event_id = ? order by id",
            String.class,
            eventId
        );

        assertEquals(4, intValue(result.get("skippedCount")));
        assertTrue(reasons.contains("event_type_mismatch"));
        assertTrue(reasons.contains("metric_missing"));
        assertTrue(reasons.contains("metric_non_numeric"));
        assertTrue(reasons.contains("time_window_unsupported"));
        assertEquals(0L, count("alerts"));
    }

    @Test
    void malformedRuleWritesErrorWithoutStoppingOtherRules() {
        var eventId = insertStandardEvent("file_operation", "high", 90, null);
        insertRule("Broken", "file_operation", "medium", "{bad json", true);
        insertRule("Matched", "file_operation", "medium", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"riskScore","operator":">=","value":80}}
            """, true);

        var result = runner.run(new RuleEvaluationRunRequest(eventId, null, "ops-user"));
        var decisions = jdbcTemplate.queryForList(
            "select decision from alert_decisions where standard_event_id = ? order by id",
            String.class,
            eventId
        );

        assertEquals(1, intValue(result.get("errorCount")));
        assertEquals(1, intValue(result.get("matchedCount")));
        assertEquals(List.of("error", "matched"), decisions);
        assertEquals(0L, count("alerts"));
    }

    @Test
    void disabledRulesAreSkippedByBulkRunAndRejectedWhenExplicit() {
        var eventId = insertStandardEvent("file_operation", "high", 90, null);
        var disabledRuleId = insertRule("Disabled", "file_operation", "high", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"riskScore","operator":">=","value":80}}
            """, false);

        var bulk = runner.run(new RuleEvaluationRunRequest(eventId, null, "ops-user"));
        var disabled = assertThrows(
            ResponseStatusException.class,
            () -> runner.run(new RuleEvaluationRunRequest(eventId, disabledRuleId, "ops-user"))
        );

        assertEquals(0, intValue(bulk.get("evaluatedCount")));
        assertEquals(HttpStatus.BAD_REQUEST, disabled.getStatusCode());
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));
    }

    @Test
    void missingInputReturnsErrorsWithoutWritingDirtyData() {
        var eventId = insertStandardEvent("file_operation", "high", 90, null);
        var missingEvent = assertThrows(
            ResponseStatusException.class,
            () -> runner.run(new RuleEvaluationRunRequest(9999L, null, "ops-user"))
        );
        var missingRule = assertThrows(
            ResponseStatusException.class,
            () -> runner.run(new RuleEvaluationRunRequest(eventId, 9999L, "ops-user"))
        );
        var missingRequiredEvent = assertThrows(
            ResponseStatusException.class,
            () -> runner.run(new RuleEvaluationRunRequest(null, null, "ops-user"))
        );

        assertEquals(HttpStatus.NOT_FOUND, missingEvent.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missingRule.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, missingRequiredEvent.getStatusCode());
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));
    }

    @Test
    void migrationConstrainsDecisionState() {
        var eventId = insertStandardEvent("file_operation", "high", 90, null);
        var ruleId = insertRule("Rule", "file_operation", "high", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"riskScore","operator":">=","value":80}}
            """, true);

        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update("""
                insert into alert_decisions(standard_event_id, rule_id, decision, detail_json)
                values (?, ?, 'unknown', '{}')
                """, eventId, ruleId)
        );
    }

    @Test
    void migrationCleansDuplicateDecisionsAndLegacyStatesBeforeAddingConstraints() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:rule_decision_migration_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
                + "CREATE DOMAIN IF NOT EXISTS JSONB AS JSON"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .clean();
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("11")
            .load()
            .migrate();

        var template = new JdbcTemplate(dataSource);
        var eventId = insertStandardEvent(template, "file_operation", "high", 90, null);
        var ruleId = insertRule(template, "Rule", "file_operation", "high", """
            {"version":1,"mode":"structured_config","timeWindow":"all_day","threshold":{"metric":"riskScore","operator":">=","value":80}}
            """, true);
        template.update("""
            insert into alert_decisions(standard_event_id, rule_id, decision, detail_json, created_at)
            values (?, ?, 'matched', cast('{}' as jsonb), timestamp '2026-05-23 10:00:00')
            """, eventId, ruleId);
        template.update("""
            insert into alert_decisions(standard_event_id, rule_id, decision, detail_json, created_at)
            values (?, ?, 'legacy_status', cast('{}' as jsonb), timestamp '2026-05-23 11:00:00')
            """, eventId, ruleId);

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        assertEquals(1L, template.queryForObject(
            "select count(*) from alert_decisions where standard_event_id = ? and rule_id = ?",
            Long.class,
            eventId,
            ruleId
        ));
        assertEquals("error", template.queryForObject(
            "select decision from alert_decisions where standard_event_id = ? and rule_id = ?",
            String.class,
            eventId,
            ruleId
        ));
        assertThrows(
            DataIntegrityViolationException.class,
            () -> template.update("""
                insert into alert_decisions(standard_event_id, rule_id, decision, detail_json)
                values (?, ?, 'matched', cast('{}' as jsonb))
                """, eventId, ruleId)
        );
    }

    private Long insertStandardEvent(String eventType, String severity, Integer riskScore, Map<String, Object> mapped) {
        return insertStandardEvent(jdbcTemplate, eventType, severity, riskScore, mapped);
    }

    private Long insertStandardEvent(
        JdbcTemplate template,
        String eventType,
        String severity,
        Integer riskScore,
        Map<String, Object> mapped
    ) {
        var normalized = Map.of(
            "sourceTable", "sec_alert_event",
            "mapped", mapped == null ? Map.of("riskScore", riskScore == null ? 0 : riskScore) : mapped
        );
        return insertAndReturnId(template, """
            insert into standard_events(
                source_system, external_id, event_type, occurred_at, actor, asset_ref,
                subject_type, subject_ref, action, result, severity, risk_score,
                normalized_json, extra_json, dedup_key
            )
            values ('sync-once', ?, ?, ?, 'zhangsan', 'WIN-01', 'event', 'WIN-01',
                    'upload', 'detected', ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
            """,
            "EVT-" + System.nanoTime(),
            eventType,
            OffsetDateTime.parse("2026-05-23T10:15:00+08:00"),
            severity,
            riskScore == null ? 0 : riskScore,
            toJson(normalized),
            toJson(Map.of("eventCount", 1)),
            "dedup-" + System.nanoTime()
        );
    }

    private Long insertRule(String name, String eventType, String severity, String expression, boolean enabled) {
        return insertRule(jdbcTemplate, name, eventType, severity, expression, enabled);
    }

    private Long insertRule(
        JdbcTemplate template,
        String name,
        String eventType,
        String severity,
        String expression,
        boolean enabled
    ) {
        return insertAndReturnId(template, """
            insert into rules(name, event_type, severity, expression, enabled)
            values (?, ?, ?, ?, ?)
            """, name, eventType, severity, expression, enabled);
    }

    private Long insertAndReturnId(String sql, Object... args) {
        return insertAndReturnId(jdbcTemplate, sql, args);
    }

    private Long insertAndReturnId(JdbcTemplate template, String sql, Object... args) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        template.update(connection -> {
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

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private String stringCell(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private int intCell(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private Map<String, Object> objectValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) map;
            return typed;
        }
        try {
            var json = value instanceof byte[] bytes
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(value);
            var node = objectMapper.readTree(json);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JSON object: " + value, ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JSON value", ex);
        }
    }
}
