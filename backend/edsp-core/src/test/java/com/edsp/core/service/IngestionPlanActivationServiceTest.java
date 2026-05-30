package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.IngestionPlanActivationRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class IngestionPlanActivationServiceTest {
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private PlanFingerprintSupport planFingerprintSupport;
    private IngestionPlanActivationService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:ingestion_plan_activation_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
        planFingerprintSupport = new PlanFingerprintSupport(objectMapper);
        service = new IngestionPlanActivationService(
            jdbcTemplate,
            objectMapper,
            new CoreRequestSupport(objectMapper),
            planFingerprintSupport
        );
    }

    @Test
    void activatePassedLatestShadowRunPersistsActivationAuditOnly() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var runId = insertFingerprintedShadowRun(planId, dataSourceId, "passed");

        var activation = service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"));

        assertEquals(planId, number(activation.get("ingestionPlanId")));
        assertEquals(dataSourceId, number(activation.get("dataSourceId")));
        assertEquals(runId, number(activation.get("shadowRunId")));
        assertEquals("active", activation.get("status"));
        assertEquals("ops-user", activation.get("activatedBy"));
        assertEquals("validated", activation.get("activationReason"));
        assertEquals("approved", planStatus(planId));
        assertEquals(1L, count("ingestion_plan_activations"));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));

        var config = readJson(jdbcTemplate.queryForObject(
            "select config_json from ingestion_plan_activations where id = ?",
            Object.class,
            activation.get("id")
        ));
        assertEquals("latest_shadow_run_passed", config.get("activationGate"));
        assertEquals(runId, number(config.get("shadowRunId")));
        assertEquals("approved", config.get("planStatus"));
        assertTrue(config.containsKey("dedupStrategy"));
        assertEquals("create_time", config.get("cursorField"));
        assertEquals(100, ((Number) config.get("batchSize")).intValue());
        assertEquals("record_failed_rows", config.get("errorPolicy"));
        assertEquals("activation record only; no data sync or alert generation", config.get("note"));
        assertPlanFingerprint(config);

        var recent = service.list(planId, 10);
        assertEquals(1, recent.size());
        assertEquals(activation.get("id"), recent.get(0).get("id"));
    }

    @Test
    void activateRejectsPassedRunWithoutPlanFingerprint() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var runId = insertShadowRun(planId, dataSourceId, "passed");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("shadow_run_plan_fingerprint_missing", ex.getReason());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @Test
    void activateRejectsPassedRunWithUnsupportedPlanFingerprintAlgorithm() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var runId = insertShadowRunWithReport(planId, dataSourceId, "passed", Map.of(
            "planFingerprint", Map.of(
                "algorithm", "sha1",
                "hash", "abc"
            )
        ));

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("shadow_run_plan_fingerprint_invalid", ex.getReason());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @Test
    void activateRejectsPassedRunWhenPlanJsonChangesAfterShadowRun() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var runId = insertFingerprintedShadowRun(planId, dataSourceId, "passed");
        jdbcTemplate.update("""
            update ingestion_plans
            set plan_json = cast(? as jsonb)
            where id = ?
            """, changedPlanJson(), planId);

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("shadow_run_stale_after_plan_edit", ex.getReason());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @Test
    void activateRejectsPassedRunWhenTransformRulePayloadChangesAfterShadowRun() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved", valueMapPlanJson("mapped-user"));
        var runId = insertFingerprintedShadowRun(planId, dataSourceId, "passed");
        jdbcTemplate.update("""
            update ingestion_plans
            set plan_json = cast(? as jsonb)
            where id = ?
            """, valueMapPlanJson("admin-user"), planId);

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("shadow_run_stale_after_plan_edit", ex.getReason());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"warning", "blocked", "failed"})
    void activateRejectsLatestShadowRunThatDidNotPass(String status) {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var runId = insertShadowRun(planId, dataSourceId, status);

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @Test
    void activateRejectsOldPassedRunWhenLatestRunIsNotPassed() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var oldPassedRunId = insertShadowRun(planId, dataSourceId, "passed");
        insertShadowRun(planId, dataSourceId, "warning");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(oldPassedRunId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @Test
    void activateRejectsShadowRunFromDifferentPlan() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var otherPlanId = insertPlan(dataSourceId, "approved");
        var otherRunId = insertShadowRun(otherPlanId, dataSourceId, "passed");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(otherRunId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @Test
    void activateRejectsPlanWithIllegalStatus() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "suggested");
        var runId = insertShadowRun(planId, dataSourceId, "passed");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0L, count("ingestion_plan_activations"));
    }

    @Test
    void activateRejectsDuplicateActiveActivation() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "shadow_ready");
        var runId = insertFingerprintedShadowRun(planId, dataSourceId, "passed");
        service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"));

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated again"))
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(1L, count("ingestion_plan_activations"));
    }

    @Test
    void deactivateOnlyUpdatesActivationAndDoesNotChangePlanStatus() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "shadow_ready");
        var runId = insertFingerprintedShadowRun(planId, dataSourceId, "passed");
        var activation = service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"));

        var deactivated = service.deactivate(
            number(activation.get("id")),
            new IngestionPlanActivationRequest(runId, "reviewer", "rollback")
        );

        assertEquals("deactivated", deactivated.get("status"));
        assertEquals("reviewer", deactivated.get("deactivatedBy"));
        assertEquals("rollback", deactivated.get("deactivationReason"));
        assertEquals("shadow_ready", planStatus(planId));
        assertEquals(1L, count("ingestion_plan_activations"));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));
    }

    private Long insertDataSource() {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values ('Activation Source', 'h2', 'database', cast('{}' as jsonb), 'active', true)
            """);
        return lastId("data_sources");
    }

    private Long insertPlan(Long dataSourceId, String status) {
        return insertPlan(dataSourceId, status, planJson());
    }

    private Long insertPlan(Long dataSourceId, String status, String planJson) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, name, status, plan_json)
            values (?, 'Activation plan', ?, cast(? as jsonb))
            """, dataSourceId, status, planJson);
        return lastId("ingestion_plans");
    }

    private Long insertShadowRun(Long planId, Long dataSourceId, String status) {
        jdbcTemplate.update("""
            insert into ingestion_plan_shadow_runs(
                ingestion_plan_id, data_source_id, status, sample_limit,
                read_count, success_count, failed_count, report_json
            )
            values (?, ?, ?, 20, 2, 2, 0, cast('{}' as jsonb))
            """, planId, dataSourceId, status);
        return lastId("ingestion_plan_shadow_runs");
    }

    private Long insertFingerprintedShadowRun(Long planId, Long dataSourceId, String status) {
        var planJson = jdbcTemplate.queryForObject(
            "select plan_json from ingestion_plans where id = ?",
            Object.class,
            planId
        );
        return insertShadowRunWithReport(planId, dataSourceId, status, Map.of(
            "planFingerprint", planFingerprintSupport.fingerprint(planJson).asMap()
        ));
    }

    private Long insertShadowRunWithReport(Long planId, Long dataSourceId, String status, Map<String, Object> report) {
        jdbcTemplate.update("""
            insert into ingestion_plan_shadow_runs(
                ingestion_plan_id, data_source_id, status, sample_limit,
                read_count, success_count, failed_count, report_json
            )
            values (?, ?, ?, 20, 2, 2, 0, cast(? as jsonb))
            """, planId, dataSourceId, status, writeJson(report));
        return lastId("ingestion_plan_shadow_runs");
    }

    private String planJson() {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt"
              },
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false}
            }
            """;
    }

    private String changedPlanJson() {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "cursorField": "updated_time",
              "fieldMappings": {
                "id": "externalId",
                "updated_time": "occurredAt"
              },
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "updated_time", "shadowOnly": true, "enabled": false}
            }
            """;
    }

    private String valueMapPlanJson(String mappedActor) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "user_account": "actor"
              },
              "fieldMappingDetails": [
                {
                  "sourceField": "user_account",
                  "standardField": "actor",
                  "transformRule": "valueMap",
                  "transformRulePayload": {
                    "type": "valueMap",
                    "values": {
                      "USER_A": "%s"
                    },
                    "onMissing": "keepOriginal"
                  }
                }
              ],
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false}
            }
            """.formatted(mappedActor);
    }

    private String planStatus(Long planId) {
        return jdbcTemplate.queryForObject(
            "select status from ingestion_plans where id = ?",
            String.class,
            planId
        );
    }

    private Long count(String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
    }

    private Long lastId(String tableName) {
        return jdbcTemplate.queryForObject("select max(id) from " + tableName, Long.class);
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private Map<String, Object> readJson(Object value) {
        if (value == null) {
            return Map.of();
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize test JSON", ex);
        }
    }

    private void assertPlanFingerprint(Map<String, Object> value) {
        var fingerprint = readJson(writeJson(value.get("planFingerprint")));
        assertEquals("sha256-canonical-json-v1", fingerprint.get("algorithm"));
        assertTrue(String.valueOf(fingerprint.get("hash")).matches("[0-9a-f]{64}"));
        assertFalse(fingerprint.containsKey("planJson"));
        assertFalse(fingerprint.containsKey("plan_json"));
    }
}
