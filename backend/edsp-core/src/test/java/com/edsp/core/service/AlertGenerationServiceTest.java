package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.AlertGenerationRunRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AlertGenerationServiceTest {
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private AlertGenerationService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:alert_generation_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
        var repository = new AlertRepository(jdbcTemplate, objectMapper, support);
        service = new AlertGenerationService(jdbcTemplate, objectMapper, support, repository);
    }

    @Test
    void matchedDecisionCreatesAlertAndIsIdempotentWithoutNotifications() {
        var eventId = insertStandardEvent();
        var ruleId = insertRule("High risk file movement", "file_operation", "high");
        var decisionId = insertDecision(eventId, ruleId, "matched", "critical", 95);

        var first = service.generate(new AlertGenerationRunRequest(decisionId));
        var second = service.generate(new AlertGenerationRunRequest(decisionId));

        assertEquals("created", first.get("action"));
        assertEquals("existing", second.get("action"));
        assertEquals(1L, count("alerts"));
        assertEquals(0L, count("notification_deliveries"));
        assertEquals(decisionId, longCell("select alert_decision_id from alerts"));
        assertEquals(eventId, longCell("select standard_event_id from alerts"));
        assertEquals(ruleId, longCell("select rule_id from alerts"));
        assertEquals("critical", stringCell("select severity from alerts"));
        assertEquals("open", stringCell("select status from alerts"));
        assertEquals("sync-once", stringCell("select source_system from alerts"));
        assertEquals("rule-decision-" + decisionId, stringCell("select external_id from alerts"));
        assertEquals("file_operation", stringCell("select alert_type from alerts"));
        assertEquals("High risk file movement", stringCell("select policy_name from alerts"));

        var detail = objectValue(jdbcTemplate.queryForObject("select cast(detail_json as varchar) from alerts", Object.class));
        assertEquals(decisionId.intValue(), intValue(detail.get("decisionId")));
        assertEquals(eventId.intValue(), intValue(detail.get("standardEventId")));
        assertEquals("High risk file movement", detail.get("ruleName"));
    }

    @Test
    void rejectsNonMatchedMissingDecisionAndDecisionWithoutStandardEvent() {
        var eventId = insertStandardEvent();
        var ruleId = insertRule("Not matched rule", "file_operation", "medium");
        var notMatchedDecisionId = insertDecision(eventId, ruleId, "not_matched", "medium", 40);
        var missingEventDecisionId = insertDecision(null, ruleId, "matched", "high", 80);

        var notMatched = assertThrows(
            ResponseStatusException.class,
            () -> service.generate(new AlertGenerationRunRequest(notMatchedDecisionId))
        );
        var missingDecision = assertThrows(
            ResponseStatusException.class,
            () -> service.generate(new AlertGenerationRunRequest(999999L))
        );
        var missingEvent = assertThrows(
            ResponseStatusException.class,
            () -> service.generate(new AlertGenerationRunRequest(missingEventDecisionId))
        );

        assertEquals(HttpStatus.BAD_REQUEST, notMatched.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missingDecision.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, missingEvent.getStatusCode());
        assertEquals(0L, count("alerts"));
        assertEquals(0L, count("notification_deliveries"));
    }

    @Test
    void requestRequiresDecisionIdAndDoesNotAcceptAlternateCreationInputs() {
        var error = assertThrows(
            ResponseStatusException.class,
            () -> service.generate(new AlertGenerationRunRequest(null))
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(0L, count("alerts"));
    }

    @Test
    void migrationAllowsLegacyAlertsAndConstrainsNonNullDecisionIds() {
        jdbcTemplate.update("""
            insert into alerts(title, severity, status, detail_json)
            values ('legacy alert', 'medium', 'open', cast('{}' as jsonb))
            """);

        var eventId = insertStandardEvent();
        var ruleId = insertRule("Rule", "file_operation", "high");
        var decisionId = insertDecision(eventId, ruleId, "matched", "high", 90);
        service.generate(new AlertGenerationRunRequest(decisionId));

        assertEquals(2L, count("alerts"));
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update("""
                insert into alerts(
                    title, severity, status, rule_id, standard_event_id, alert_decision_id, detail_json
                )
                values ('duplicate', 'high', 'open', ?, ?, ?, cast('{}' as jsonb))
                """, ruleId, eventId, decisionId)
        );
    }

    private Long insertStandardEvent() {
        return insertAndReturnId("""
            insert into standard_events(
                source_system, external_id, event_type, occurred_at, actor, asset_ref,
                subject_type, subject_ref, action, result, severity, risk_score,
                normalized_json, extra_json, dedup_key
            )
            values ('sync-once', ?, 'file_operation', ?, 'zhangsan', 'WIN-01', 'asset', 'WIN-01',
                    'upload', 'detected', 'high', 95, cast(? as jsonb), cast(? as jsonb), ?)
            """,
            "EVT-" + System.nanoTime(),
            OffsetDateTime.parse("2026-05-23T10:15:00+08:00"),
            toJson(Map.of("sourceTable", "sec_alert_event", "mapped", Map.of("riskScore", 95))),
            toJson(Map.of("eventCount", 1)),
            "dedup-" + System.nanoTime()
        );
    }

    private Long insertRule(String name, String eventType, String severity) {
        return insertAndReturnId("""
            insert into rules(name, event_type, severity, expression, enabled)
            values (?, ?, ?, '{"version":1,"mode":"structured_config"}', true)
            """, name, eventType, severity);
    }

    private Long insertDecision(Long eventId, Long ruleId, String decision, String severity, int riskScore) {
        return insertAndReturnId("""
            insert into alert_decisions(
                standard_event_id, rule_id, decision, severity, risk_score, reason, detail_json
            )
            values (?, ?, ?, ?, ?, 'threshold_matched', cast(? as jsonb))
            """, eventId, ruleId, decision, severity, riskScore, toJson(Map.of("metric", "riskScore")));
    }

    private Long insertAndReturnId(String sql, Object... args) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
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

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private Long longCell(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private String stringCell(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private Map<String, Object> objectValue(Object value) {
        try {
            var node = objectMapper.readTree(String.valueOf(value));
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to parse test json", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize test json", ex);
        }
    }
}
