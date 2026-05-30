package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.IngestionPlanActivationRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class IngestionPlanActivationTransactionTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private PlanFingerprintSupport planFingerprintSupport;
    private IngestionPlanActivationService service;
    private TransactionTemplate transactionTemplate;

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
        objectMapper = context.getBean(ObjectMapper.class);
        planFingerprintSupport = context.getBean(PlanFingerprintSupport.class);
        service = context.getBean(IngestionPlanActivationService.class);
        transactionTemplate = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void activateWaitsForPlanRowLockBeforeCreatingActiveActivation() throws Exception {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "approved");
        var runId = insertShadowRun(planId, dataSourceId, "passed");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var lockAcquired = new java.util.concurrent.CountDownLatch(1);
            var releaseLock = new java.util.concurrent.CountDownLatch(1);
            var lockFuture = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("select id from ingestion_plans where id = ? for update", Long.class, planId);
                lockAcquired.countDown();
                try {
                    assertTrue(releaseLock.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }));
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

            var activationFuture = executor.submit(() ->
                service.activate(planId, new IngestionPlanActivationRequest(runId, "ops-user", "validated"))
            );

            Thread.sleep(200);
            assertFalse(activationFuture.isDone());
            releaseLock.countDown();

            assertEquals("active", activationFuture.get(5, TimeUnit.SECONDS).get("status"));
            lockFuture.get(5, TimeUnit.SECONDS);
            assertEquals(1L, activeActivationCount(planId));
            assertEquals("approved", planStatus(planId));
        } finally {
            executor.shutdownNow();
        }
    }

    private Long insertDataSource() {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values ('Activation Source', 'h2', 'database', cast('{}' as jsonb), 'active', true)
            """);
        return lastId("data_sources");
    }

    private Long insertPlan(Long dataSourceId, String status) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, name, status, plan_json)
            values (?, 'Activation plan', ?, cast(? as jsonb))
            """, dataSourceId, status, """
                {
                  "version": "ingestion-plan-v1",
                  "mode": "database_polling",
                  "cursorField": "create_time",
                  "dedupStrategy": {"type": "external_id", "fields": ["id"]}
                }
                """);
        return lastId("ingestion_plans");
    }

    private Long insertShadowRun(Long planId, Long dataSourceId, String status) {
        var planJson = jdbcTemplate.queryForObject(
            "select plan_json from ingestion_plans where id = ?",
            Object.class,
            planId
        );
        jdbcTemplate.update("""
            insert into ingestion_plan_shadow_runs(
                ingestion_plan_id, data_source_id, status, sample_limit,
                read_count, success_count, failed_count, report_json
            )
            values (?, ?, ?, 20, 2, 2, 0, cast(? as jsonb))
            """, planId, dataSourceId, status, writeJson(Map.of(
                "planFingerprint", planFingerprintSupport.fingerprint(planJson).asMap()
            )));
        return lastId("ingestion_plan_shadow_runs");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize test JSON", ex);
        }
    }

    private Long activeActivationCount(Long planId) {
        return jdbcTemplate.queryForObject("""
            select count(*)
            from ingestion_plan_activations
            where ingestion_plan_id = ? and status = 'active'
            """, Long.class, planId);
    }

    private String planStatus(Long planId) {
        return jdbcTemplate.queryForObject("select status from ingestion_plans where id = ?", String.class, planId);
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
                "jdbc:h2:mem:ingestion_plan_activation_transaction_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
        PlanFingerprintSupport planFingerprintSupport(ObjectMapper objectMapper) {
            return new PlanFingerprintSupport(objectMapper);
        }

        @Bean
        IngestionPlanActivationService activationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CoreRequestSupport support,
            PlanFingerprintSupport planFingerprintSupport
        ) {
            return new IngestionPlanActivationService(jdbcTemplate, objectMapper, support, planFingerprintSupport);
        }
    }
}
