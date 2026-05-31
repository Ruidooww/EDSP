package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.edsp.core.service.AlertGenerationService.AlertCandidate;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class AlertRepositoryPostgresqlConcurrencyTest {
    private Flyway flyway;
    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        var url = System.getenv("EDSP_ALERT_PG_VERIFY_URL");
        var username = System.getenv("EDSP_ALERT_PG_VERIFY_USERNAME");
        var password = System.getenv("EDSP_ALERT_PG_VERIFY_PASSWORD");
        assumeTrue(url != null && username != null && password != null);
        assumeTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/edsp_alert_pg_verify"));

        var dataSource = new DriverManagerDataSource(url, username, password);
        flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void concurrentNestedTransactionsRecoverAsCreatedAndExisting() throws Exception {
        var ruleId = insertAndReturnId("""
            insert into rules(name, event_type, severity, expression, enabled)
            values ('PostgreSQL concurrency verification', 'file_operation', 'high',
                    '{"version":1,"mode":"structured_config"}', true)
            """);
        var standardEventId = insertAndReturnId("""
            insert into standard_events(
                source_system, external_id, event_type, occurred_at, actor, asset_ref,
                subject_type, subject_ref, action, result, severity, risk_score,
                normalized_json, extra_json, dedup_key
            )
            values ('postgres-concurrency-verification', 'EVT-PG-RACE', 'file_operation', now(),
                    'verification-user', 'verification-asset', 'asset', 'verification-asset',
                    'upload', 'detected', 'high', 95, '{}'::jsonb, '{}'::jsonb, 'dedup-pg-race')
            """);
        var decisionId = insertAndReturnId("""
            insert into alert_decisions(
                standard_event_id, rule_id, decision, severity, risk_score, reason, detail_json
            )
            values (?, ?, 'matched', 'high', 95, 'threshold_matched', '{}'::jsonb)
            """, standardEventId, ruleId);
        var support = new CoreRequestSupport(objectMapper);
        var repository = new BarrierAlertRepository(jdbcTemplate, objectMapper, support);
        var candidate = new AlertCandidate(
            decisionId,
            standardEventId,
            ruleId,
            "PostgreSQL concurrency verification alert",
            "high",
            "asset",
            "verification-asset",
            "postgres-concurrency-verification",
            "rule-decision-" + decisionId,
            "file_operation",
            Timestamp.from(Instant.parse("2026-05-31T00:00:00Z")),
            "verification-user",
            "verification-asset",
            "PostgreSQL concurrency verification",
            Map.of("verification", "postgresql_concurrency")
        );

        var results = runConcurrentNestedTransactions(repository, candidate);
        var actions = results.stream().map(WorkerResult::action).sorted().toList();

        assertEquals(List.of("created", "existing"), actions);
        assertTrue(results.stream().allMatch(WorkerResult::outerTransactionUsable));
        assertEquals(1L, count("select count(*) from alerts where alert_decision_id = ?", decisionId));
        assertEquals(0L, count("select count(*) from notification_deliveries"));
        assertEquals(0L, count("select count(*) from alert_lifecycle_events"));
        assertEquals("open", jdbcTemplate.queryForObject(
            "select status from alerts where alert_decision_id = ?",
            String.class,
            decisionId
        ));
        assertNull(jdbcTemplate.queryForObject(
            "select acknowledged_at from alerts where alert_decision_id = ?",
            Object.class,
            decisionId
        ));
        assertNull(jdbcTemplate.queryForObject(
            "select closed_at from alerts where alert_decision_id = ?",
            Object.class,
            decisionId
        ));
        assertNull(jdbcTemplate.queryForObject(
            "select assigned_to from alerts where alert_decision_id = ?",
            String.class,
            decisionId
        ));

        writeSafeResult(Map.of(
            "createdAlertCount", 1,
            "existingAlertCount", 1,
            "notificationDeliveryCount", 0,
            "outerTransactionUsable", true,
            "lifecycleSideEffectCount", 0
        ));
    }

    private List<WorkerResult> runConcurrentNestedTransactions(
        AlertRepository repository,
        AlertCandidate candidate
    ) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                executor.submit(() -> createInsideNestedTransaction(repository, candidate)),
                executor.submit(() -> createInsideNestedTransaction(repository, candidate))
            );
            var results = new ArrayList<WorkerResult>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private WorkerResult createInsideNestedTransaction(
        AlertRepository repository,
        AlertCandidate candidate
    ) {
        var outer = new TransactionTemplate(transactionManager);
        var nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return outer.execute(status -> {
            var alert = nested.execute(nestedStatus -> repository.createFromDecision(candidate));
            var probe = jdbcTemplate.queryForObject("select 1", Integer.class);
            return new WorkerResult(String.valueOf(alert.get("action")), Integer.valueOf(1).equals(probe));
        });
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

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private void writeSafeResult(Map<String, Object> result) throws Exception {
        var resultPath = System.getenv("EDSP_ALERT_PG_VERIFY_RESULT_PATH");
        if (resultPath != null && !resultPath.isBlank()) {
            Files.writeString(Path.of(resultPath), objectMapper.writeValueAsString(result));
        }
    }

    private record WorkerResult(String action, boolean outerTransactionUsable) {
    }

    private static final class BarrierAlertRepository extends AlertRepository {
        private final CyclicBarrier lookupBarrier = new CyclicBarrier(2);
        private final ThreadLocal<Boolean> firstLookup = ThreadLocal.withInitial(() -> true);

        private BarrierAlertRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CoreRequestSupport support
        ) {
            super(jdbcTemplate, objectMapper, support);
        }

        @Override
        public Map<String, Object> findByDecisionId(long decisionId) {
            var existing = super.findByDecisionId(decisionId);
            if (firstLookup.get()) {
                firstLookup.set(false);
                if (existing == null) {
                    awaitLookupBarrier();
                }
            }
            return existing;
        }

        private void awaitLookupBarrier() {
            try {
                lookupBarrier.await(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new IllegalStateException("Concurrent lookup barrier failed", ex);
            }
        }
    }
}
