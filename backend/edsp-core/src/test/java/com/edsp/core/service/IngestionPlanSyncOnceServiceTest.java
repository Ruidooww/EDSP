package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.IngestionPlanSyncOnceRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class IngestionPlanSyncOnceServiceTest {
    private static final String SOURCE_URL =
        "jdbc:h2:mem:sync_once_source_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private IngestionPlanSyncOnceService service;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:ingestion_plan_sync_once_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
        service = new IngestionPlanSyncOnceService(
            jdbcTemplate,
            objectMapper,
            support,
            new JdbcShadowSampleService(objectMapper),
            new StandardEventDedupService(jdbcTemplate, support)
        );
        resetSourceDatabase();
    }

    @Test
    void syncOnceRequiresActiveActivationAndWritesRawAndStandardEventsWithoutAlerts() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId);
        insertDefaultFields(tableId, scanRunId);
        var planId = insertPlan(dataSourceId, scanRunId, tableId);
        var shadowRunId = insertShadowRun(planId, dataSourceId, "passed");
        var activationId = insertActivation(planId, dataSourceId, shadowRunId, "active");

        var result = service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"));

        assertEquals("passed", result.get("status"));
        assertEquals(2, intValue(result.get("readCount")));
        assertEquals(2, intValue(result.get("successCount")));
        assertEquals(0, intValue(result.get("failedCount")));
        assertEquals(0, intValue(result.get("duplicateCount")));
        assertEquals(2, intValue(result.get("rawCount")));
        assertEquals(2, intValue(result.get("standardCount")));
        assertEquals(1L, count("ingestion_plan_sync_runs"));
        assertEquals(1L, count("ingestion_runs"));
        assertEquals(2L, count("raw_events"));
        assertEquals(2L, count("standard_events"));
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));
        assertEquals(number(result.get("ingestionRunId")), jdbcTemplate.queryForObject(
            "select distinct run_id from raw_events",
            Long.class
        ));
        assertEquals("standardized", jdbcTemplate.queryForObject(
            "select status from raw_events where external_id = 'ALERT-1'",
            String.class
        ));
        assertEquals("zhangsan", jdbcTemplate.queryForObject(
            "select actor from standard_events where external_id = 'ALERT-1'",
            String.class
        ));
        assertEquals("high", jdbcTemplate.queryForObject(
            "select severity from standard_events where external_id = 'ALERT-1'",
            String.class
        ));

        var report = objectValue(jdbcTemplate.queryForObject(
            "select report_json from ingestion_plan_sync_runs where id = ?",
            Object.class,
            result.get("id")
        ));
        assertEquals("sync_once", report.get("mode"));
        assertEquals("Sync Once writes raw_events and standard_events only; no alerts or notifications", report.get("boundary"));
    }

    @Test
    void syncOnceRejectsDeactivatedOrMissingActivationWithoutWritingEvents() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId);
        insertDefaultFields(tableId, scanRunId);
        var planId = insertPlan(dataSourceId, scanRunId, tableId);
        var shadowRunId = insertShadowRun(planId, dataSourceId, "passed");
        var activationId = insertActivation(planId, dataSourceId, shadowRunId, "deactivated");

        var deactivated = assertThrows(
            ResponseStatusException.class,
            () -> service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"))
        );
        assertEquals(HttpStatus.BAD_REQUEST, deactivated.getStatusCode());

        var missing = assertThrows(
            ResponseStatusException.class,
            () -> service.syncOnce(99999L, new IngestionPlanSyncOnceRequest(20, "ops-user"))
        );
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(0L, count("ingestion_plan_sync_runs"));
        assertEquals(0L, count("ingestion_runs"));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
    }

    @Test
    void syncOnceRejectsActivationWhenLatestShadowRunIsNotThePassedActivationRun() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId);
        insertDefaultFields(tableId, scanRunId);
        var planId = insertPlan(dataSourceId, scanRunId, tableId);
        var passedRunId = insertShadowRun(planId, dataSourceId, "passed");
        insertShadowRun(planId, dataSourceId, "warning");
        var activationId = insertActivation(planId, dataSourceId, passedRunId, "active");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
    }

    @Test
    void syncOnceIsIdempotentForStandardEventsWhenRowsAreReadAgain() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId);
        insertDefaultFields(tableId, scanRunId);
        var planId = insertPlan(dataSourceId, scanRunId, tableId);
        var shadowRunId = insertShadowRun(planId, dataSourceId, "passed");
        var activationId = insertActivation(planId, dataSourceId, shadowRunId, "active");

        service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"));
        var second = service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"));

        assertEquals("passed", second.get("status"));
        assertEquals(2, intValue(second.get("duplicateCount")));
        assertEquals(4L, count("raw_events"));
        assertEquals(2L, count("standard_events"));
        assertEquals(0L, count("alerts"));
    }

    @Test
    void syncOnceUsesExplicitDedupFieldsForStandardEventIdempotency() throws Exception {
        executeSourceSql("update sec_alert_event set event_name = 'Same vendor event'");
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId);
        insertDefaultFields(tableId, scanRunId);
        var planId = insertPlan(dataSourceId, scanRunId, tableId, planJsonWithDedupFields(tableId, "[\"event_name\"]"));
        var shadowRunId = insertShadowRun(planId, dataSourceId, "passed");
        var activationId = insertActivation(planId, dataSourceId, shadowRunId, "active");

        var result = service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"));

        assertEquals("passed", result.get("status"));
        assertEquals(1, intValue(result.get("duplicateCount")));
        assertEquals(2L, count("raw_events"));
        assertEquals(1L, count("standard_events"));
    }

    @Test
    void syncOnceRejectsActivationWhenDataSourceDoesNotMatchPlanBeforeWritingRuns() {
        var dataSourceId = insertDataSource();
        var otherDataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId);
        insertDefaultFields(tableId, scanRunId);
        var planId = insertPlan(dataSourceId, scanRunId, tableId);
        var shadowRunId = insertShadowRun(planId, dataSourceId, "passed");
        var activationId = insertActivation(planId, otherDataSourceId, shadowRunId, "active");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0L, count("ingestion_plan_sync_runs"));
        assertEquals(0L, count("ingestion_runs"));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
    }

    @Test
    void syncOnceRecordsPartialRowFailureAsWarningWithoutFailingWholeRun() throws Exception {
        executeSourceSql("update sec_alert_event set create_time = 'not-a-time' where id = 'ALERT-2'");
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId);
        insertDefaultFields(tableId, scanRunId);
        var planId = insertPlan(dataSourceId, scanRunId, tableId);
        var shadowRunId = insertShadowRun(planId, dataSourceId, "passed");
        var activationId = insertActivation(planId, dataSourceId, shadowRunId, "active");

        var result = service.syncOnce(activationId, new IngestionPlanSyncOnceRequest(20, "ops-user"));

        assertEquals("warning", result.get("status"));
        assertEquals(2, intValue(result.get("readCount")));
        assertEquals(1, intValue(result.get("successCount")));
        assertEquals(1, intValue(result.get("failedCount")));
        assertEquals(2, intValue(result.get("rawCount")));
        assertEquals(1, intValue(result.get("standardCount")));
        assertEquals(2L, count("raw_events"));
        assertEquals(1L, count("standard_events"));
        assertEquals("standardize_failed", jdbcTemplate.queryForObject(
            "select status from raw_events where external_id = 'ALERT-2'",
            String.class
        ));
        var report = objectValue(jdbcTemplate.queryForObject(
            "select report_json from ingestion_plan_sync_runs where id = ?",
            Object.class,
            result.get("id")
        ));
        assertTrue(stringList(report.get("warnings")).contains("partial_row_failure"));
        assertEquals(1, intValue(objectValue(report.get("errorsByType")).get("invalid_time_format")));
        var listed = service.listByPlan(planId, 5);
        assertTrue(stringList(objectValue(listed.get(0).get("report")).get("warnings")).contains("partial_row_failure"));
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));
    }

    private void resetSourceDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(SOURCE_URL, "sa", "super-secret");
             var statement = connection.createStatement()) {
            statement.execute("drop table if exists sec_alert_event");
            statement.execute("""
                create table sec_alert_event(
                    id varchar(40),
                    create_time varchar(40),
                    event_name varchar(200),
                    user_account varchar(80),
                    host_name varchar(80),
                    risk_level varchar(40)
                )
                """);
            statement.executeUpdate("""
                insert into sec_alert_event(id, create_time, event_name, user_account, host_name, risk_level)
                values
                ('ALERT-1', '2026-05-20 10:30:00', 'Sensitive file export', 'zhangsan', 'WIN-01', 'high'),
                ('ALERT-2', '2026-05-20 10:31:00', 'Suspicious login', 'lisi', 'WIN-02', 'medium')
                """);
        }
    }

    private void executeSourceSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(SOURCE_URL, "sa", "super-secret");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Long insertDataSource() {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values ('Sync Source', 'h2', 'database', cast(? as jsonb), 'active', true)
            """, "{\"jdbcUrl\":\"" + SOURCE_URL + "\",\"username\":\"sa\",\"password\":\"super-secret\"}");
        return lastId("data_sources");
    }

    private Long insertCompleteScan(Long dataSourceId) {
        jdbcTemplate.update("""
            insert into schema_scan_runs(
                data_source_id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
            )
            values (?, 'success', 1, 1, 0, 6, 6)
            """, dataSourceId);
        return lastId("schema_scan_runs");
    }

    private Long insertTable(Long dataSourceId, Long scanRunId) {
        jdbcTemplate.update("""
            insert into schema_tables(
                data_source_id, scan_run_id, schema_name, table_name, category, confirmation_status, lifecycle_status
            )
            values (?, ?, 'public', 'sec_alert_event', 'alert_table', 'confirmed', 'active')
            """, dataSourceId, scanRunId);
        return lastId("schema_tables");
    }

    private void insertDefaultFields(Long tableId, Long scanRunId) {
        insertField(tableId, scanRunId, "id", "varchar", 1);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2);
        insertField(tableId, scanRunId, "event_name", "varchar", 3);
        insertField(tableId, scanRunId, "user_account", "varchar", 4);
        insertField(tableId, scanRunId, "host_name", "varchar", 5);
        insertField(tableId, scanRunId, "risk_level", "varchar", 6);
    }

    private void insertField(Long tableId, Long scanRunId, String fieldName, String fieldType, int ordinal) {
        jdbcTemplate.update("""
            insert into schema_fields(
                schema_table_id, scan_run_id, field_name, field_type, sample_value,
                ordinal_position, confidence, lifecycle_status
            )
            values (?, ?, ?, ?, ?, ?, 90, 'active')
            """, tableId, scanRunId, fieldName, fieldType, fieldName + "-sample", ordinal);
    }

    private Long insertPlan(Long dataSourceId, Long scanRunId, Long tableId) {
        return insertPlan(dataSourceId, scanRunId, tableId, planJson(tableId));
    }

    private Long insertPlan(Long dataSourceId, Long scanRunId, Long tableId, String planJson) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Sync plan', 'shadow_ready', cast(? as jsonb))
            """, dataSourceId, scanRunId, planJson);
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

    private Long insertActivation(Long planId, Long dataSourceId, Long shadowRunId, String status) {
        jdbcTemplate.update("""
            insert into ingestion_plan_activations(
                ingestion_plan_id, data_source_id, shadow_run_id, status,
                activated_by, activation_reason, config_json
            )
            values (?, ?, ?, ?, 'ops-user', 'validated', cast('{}' as jsonb))
            """, planId, dataSourceId, shadowRunId, status);
        return lastId("ingestion_plan_activations");
    }

    private String planJson(Long tableId) {
        return planJsonWithDedupFields(tableId, "[\"id\"]");
    }

    private String planJsonWithDedupFields(Long tableId, String dedupFieldsJson) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "event_name": "title",
                "user_account": "actor",
                "host_name": "assetRef",
                "risk_level": "severity"
              },
              "dedupStrategy": {"type": "external_id", "fields": %s, "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false},
              "risks": [],
              "requiredFieldsMissing": []
            }
            """.formatted(tableId, dedupFieldsJson);
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

    private int intValue(Object value) {
        return ((Number) value).intValue();
    }

    private Map<String, Object> objectValue(Object value) {
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

    private List<String> stringList(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }
}
