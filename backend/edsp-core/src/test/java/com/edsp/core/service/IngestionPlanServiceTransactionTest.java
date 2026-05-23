package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.edsp.core.dto.IngestionPlanGenerateRequest;
import com.edsp.core.dto.IngestionPlanStatusRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

class IngestionPlanServiceTransactionTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbcTemplate;
    private IngestionPlanService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        var dataSource = context.getBean(DataSource.class);
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

        jdbcTemplate = context.getBean(JdbcTemplate.class);
        service = context.getBean(IngestionPlanService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void generateRollsBackSemanticProfilingWhenPlanInsertFailsThroughSpringProxy() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var alertTableId = insertTable(dataSourceId, scanRunId, "SEC_ALERT_EVENT", "alert_table");
        insertField(alertTableId, scanRunId, "ID", "varchar", "ALERT-1", 1);
        insertField(alertTableId, scanRunId, "CREATE_TIME", "timestamp", "2026-05-20 10:30:00", 2);
        insertField(alertTableId, scanRunId, "USER_ACCOUNT", "varchar", "zhangsan", 3);
        insertField(alertTableId, scanRunId, "HOST_NAME", "varchar", "WIN-01", 4);
        insertField(alertTableId, scanRunId, "SEVERITY", "varchar", "high", 5);
        jdbcTemplate.execute("alter table ingestion_plans add constraint fail_suggested_plan check (status <> 'suggested')");

        assertThrows(
            DataIntegrityViolationException.class,
            () -> service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId))
        );

        assertEquals(0L, count("ingestion_plans"));
        assertEquals(0L, jdbcTemplate.queryForObject("""
            select count(*)
            from schema_fields
            where schema_table_id = ? and semantic_type is not null
            """, Long.class, alertTableId));
    }

    @Test
    void updateStatusRejectsRestoringHistoricalPlanThroughSpringProxy() {
        var dataSourceId = insertDataSource();
        var rejectedPlanId = insertPlan(dataSourceId, "rejected", 101L, "alert_table");
        var mutablePlanId = insertPlan(dataSourceId, "suggested", 101L, "alert_table");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.updateStatus(rejectedPlanId, new IngestionPlanStatusRequest("suggested"))
        );

        assertEquals("Invalid ingestion plan status transition: rejected -> suggested", ex.getReason());
        assertEquals("rejected", planStatus(rejectedPlanId));
        assertEquals("suggested", planStatus(mutablePlanId));
    }

    private Long insertDataSource() {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values ('DLP Alert DB', 'security_platform', 'database', cast('{}' as jsonb), 'active', true)
            """);
        return lastId("data_sources");
    }

    private Long insertCompleteScan(Long dataSourceId) {
        jdbcTemplate.update("""
            insert into schema_scan_runs(
                data_source_id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
            )
            values (?, 'success', 1, 1, 0, 5, 5)
            """, dataSourceId);
        return lastId("schema_scan_runs");
    }

    private Long insertTable(Long dataSourceId, Long scanRunId, String tableName, String category) {
        jdbcTemplate.update("""
            insert into schema_tables(
                data_source_id, scan_run_id, table_name, category, confirmation_status, lifecycle_status
            )
            values (?, ?, ?, ?, 'confirmed', 'active')
            """, dataSourceId, scanRunId, tableName, category);
        return lastId("schema_tables");
    }

    private void insertField(
        Long schemaTableId,
        Long scanRunId,
        String fieldName,
        String fieldType,
        String sampleValue,
        int ordinalPosition
    ) {
        jdbcTemplate.update("""
            insert into schema_fields(
                schema_table_id, scan_run_id, field_name, field_type, sample_value,
                ordinal_position, confidence, lifecycle_status
            )
            values (?, ?, ?, ?, ?, ?, 60, 'active')
            """, schemaTableId, scanRunId, fieldName, fieldType, sampleValue, ordinalPosition);
    }

    private Long count(String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
    }

    private Long insertPlan(Long dataSourceId, String status, long schemaTableId, String templateKey) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, name, status, plan_json)
            values (?, 'Generated plan', ?, cast(? as jsonb))
            """,
            dataSourceId,
            status,
            """
                {
                  "schemaTableId": %d,
                  "mode": "database_polling",
                  "templateMatch": { "templateKey": "%s" }
                }
                """.formatted(schemaTableId, templateKey));
        return lastId("ingestion_plans");
    }

    private String planStatus(Long planId) {
        return jdbcTemplate.queryForObject(
            "select status from ingestion_plans where id = ?",
            String.class,
            planId
        );
    }

    private Long lastId(String tableName) {
        return jdbcTemplate.queryForObject("select max(id) from " + tableName, Long.class);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            var dataSource = new JdbcDataSource();
            dataSource.setURL(
                "jdbc:h2:mem:ingestion_plan_transaction_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                    + "INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
                    + "CREATE DOMAIN IF NOT EXISTS JSONB AS JSON"
            );
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CoreRequestSupport support(ObjectMapper objectMapper) {
            return new CoreRequestSupport(objectMapper);
        }

        @Bean
        SemanticProfilerService profiler(JdbcTemplate jdbcTemplate, CoreRequestSupport support) {
            return new SemanticProfilerService(jdbcTemplate, support);
        }

        @Bean
        TemplateMatcherService matcher() {
            return new TemplateMatcherService();
        }

        @Bean
        IngestionPlanService ingestionPlanService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CoreRequestSupport support,
            SemanticProfilerService profiler,
            TemplateMatcherService matcher,
            IngestionPlanPrecheckService precheckService
        ) {
            return new IngestionPlanService(jdbcTemplate, objectMapper, support, profiler, matcher, precheckService);
        }

        @Bean
        IngestionPlanPrecheckService precheckService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CoreRequestSupport support
        ) {
            return new IngestionPlanPrecheckService(jdbcTemplate, objectMapper, support);
        }
    }
}
