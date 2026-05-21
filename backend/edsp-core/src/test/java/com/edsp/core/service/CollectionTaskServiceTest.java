package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CollectionTaskServiceTest {
    private JdbcTemplate jdbcTemplate;
    private CollectionTaskService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:collection_task_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
                + "DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
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
        var objectMapper = new ObjectMapper();
        service = new CollectionTaskService(jdbcTemplate, new CoreRequestSupport(objectMapper), objectMapper);
    }

    @Test
    void startRunCollectsConfirmedSchemaIntoRawAndStandardEvents() {
        var dataSourceId = insertDataSource();
        var schemaTableId = insertSchema(dataSourceId);
        insertMappedField(schemaTableId, "event_id", "varchar", "evt-1001", "externalId", 1);
        insertMappedField(schemaTableId, "risk_type", "varchar", "file_export", "eventType", 2);
        insertMappedField(schemaTableId, "event_time", "datetime", "2026-05-20 10:30:00", "occurredAt", 3);
        insertMappedField(schemaTableId, "username", "varchar", "Administrator", "actor", 4);
        insertMappedField(schemaTableId, "host_name", "varchar", "WIN-SERVER-01", "assetRef", 5);
        insertMappedField(schemaTableId, "risk_level", "varchar", "high", "severity", 6);
        insertMappedField(schemaTableId, "operation", "varchar", "copy_to_usb", "action", 7);
        var taskId = insertCollectionTask(dataSourceId);

        var run = service.startRun(taskId, "manual");

        assertEquals("success", run.get("status"));
        assertEquals(1L, count("raw_events"));
        assertEquals(1L, count("standard_events"));
        assertEquals("idle", jdbcTemplate.queryForObject(
            "select status from collection_tasks where id = ?",
            String.class,
            taskId
        ));
        assertEquals("standardized", jdbcTemplate.queryForObject(
            "select status from raw_events",
            String.class
        ));
        assertEquals("Administrator", jdbcTemplate.queryForObject(
            "select actor from standard_events",
            String.class
        ));
        assertEquals("high", jdbcTemplate.queryForObject(
            "select severity from standard_events",
            String.class
        ));
        assertEquals(1L, jdbcTemplate.queryForObject(
            "select read_count from ingestion_runs where id = ?",
            Long.class,
            run.get("id")
        ));
        assertEquals(1L, jdbcTemplate.queryForObject(
            "select success_count from ingestion_runs where id = ?",
            Long.class,
            run.get("id")
        ));
    }

    private Long insertDataSource() {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values ('DLP Alert DB', 'security_platform', 'database', cast('{}' as jsonb), 'active', true)
            """);
        return lastId("data_sources");
    }

    private Long insertSchema(Long dataSourceId) {
        jdbcTemplate.update("""
            insert into schema_tables(data_source_id, table_name, category, confirmation_status, lifecycle_status)
            values (?, 'dlp_alert_event', 'dlp', 'confirmed', 'active')
            """, dataSourceId);
        return lastId("schema_tables");
    }

    private void insertMappedField(
        Long schemaTableId,
        String fieldName,
        String fieldType,
        String sampleValue,
        String standardField,
        int ordinalPosition
    ) {
        jdbcTemplate.update("""
            insert into schema_fields(
                schema_table_id, field_name, field_type, sample_value,
                ordinal_position, lifecycle_status, confidence
            )
            values (?, ?, ?, ?, ?, 'active', 90)
            """, schemaTableId, fieldName, fieldType, sampleValue, ordinalPosition);
        jdbcTemplate.update("""
            insert into field_mappings(schema_table_id, source_field, standard_field)
            values (?, ?, ?)
            """, schemaTableId, fieldName, standardField);
    }

    private Long insertCollectionTask(Long dataSourceId) {
        jdbcTemplate.update("""
            insert into collection_tasks(data_source_id, name, task_type, schedule_mode, status, enabled, config_json)
            values (?, 'DLP Alert Collection', 'pull', 'manual', 'idle', true, cast('{}' as jsonb))
            """, dataSourceId);
        return lastId("collection_tasks");
    }

    private Long count(String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
    }

    private Long lastId(String tableName) {
        return jdbcTemplate.queryForObject("select max(id) from " + tableName, Long.class);
    }
}
