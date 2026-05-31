package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.dto.AlertGenerationRunRequest;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MatchedAlertDecisionAutoPipelineServiceTest {
    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:matched_alert_decision_auto_pipeline_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists standard_probe");
        jdbcTemplate.execute("drop table if exists alert_decisions");
        jdbcTemplate.execute("create table standard_probe(id bigint primary key)");
        jdbcTemplate.execute("""
            create table alert_decisions(
                id bigint primary key,
                standard_event_id bigint,
                rule_id bigint,
                decision varchar(32)
            )
            """);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @Test
    void emptyStandardEventListReturnsSkippedSummary() {
        var service = new MatchedAlertDecisionAutoPipelineService(
            jdbcTemplate,
            transactionManager,
            new StubAlertGenerationService()
        );

        var summary = service.generateForNewStandardEvents(List.of());

        assertEquals("skipped", summary.status());
        assertEquals(0, summary.candidateDecisionCount());
        assertEquals(0, summary.createdAlertCount());
        assertEquals(0, summary.existingAlertCount());
        assertEquals(0, summary.failedDecisionCount());
        assertEquals(Map.of(), summary.errorsByType());
    }

    @Test
    void matchedDecisionsOnlyAreAggregatedIntoCreatedAndExistingCounts() {
        jdbcTemplate.update("insert into alert_decisions(id, standard_event_id, rule_id, decision) values (11, 1, 100, 'matched')");
        jdbcTemplate.update("insert into alert_decisions(id, standard_event_id, rule_id, decision) values (12, 1, 101, 'not_matched')");
        jdbcTemplate.update("insert into alert_decisions(id, standard_event_id, rule_id, decision) values (13, 2, 102, 'matched')");
        jdbcTemplate.update("insert into alert_decisions(id, standard_event_id, rule_id, decision) values (14, 3, null, 'matched')");
        var service = new MatchedAlertDecisionAutoPipelineService(
            jdbcTemplate,
            transactionManager,
            new StubAlertGenerationService(13L)
        );

        var summary = service.generateForNewStandardEvents(List.of(1L, 2L, 3L));

        assertEquals("passed", summary.status());
        assertEquals(2, summary.candidateDecisionCount());
        assertEquals(1, summary.createdAlertCount());
        assertEquals(1, summary.existingAlertCount());
        assertEquals(0, summary.failedDecisionCount());
    }

    @Test
    void failedCandidateRollsBackOnlyItsSavepointAndPreservesOuterWrites() {
        jdbcTemplate.update("insert into alert_decisions(id, standard_event_id, rule_id, decision) values (21, 1, 100, 'matched')");
        jdbcTemplate.update("insert into alert_decisions(id, standard_event_id, rule_id, decision) values (22, 2, 101, 'matched')");
        var service = new MatchedAlertDecisionAutoPipelineService(
            jdbcTemplate,
            transactionManager,
            new StubAlertGenerationService(22L, true)
        );
        var outer = new TransactionTemplate(transactionManager);

        var summary = outer.execute(status -> {
            jdbcTemplate.update("insert into standard_probe(id) values (1), (2)");
            return service.generateForNewStandardEvents(List.of(1L, 2L));
        });

        assertEquals(2L, count("standard_probe"));
        assertEquals("warning", summary.status());
        assertEquals(2, summary.candidateDecisionCount());
        assertEquals(1, summary.createdAlertCount());
        assertEquals(0, summary.existingAlertCount());
        assertEquals(1, summary.failedDecisionCount());
        assertEquals(Map.of("alert_generation_auto_failed", 1), summary.errorsByType());
        assertEquals("Automatic alert generation failed", summary.errorMessage());
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private static final class StubAlertGenerationService extends AlertGenerationService {
        private final Long existingDecisionId;
        private final boolean failOnExistingDecision;

        private StubAlertGenerationService() {
            this(null, false);
        }

        private StubAlertGenerationService(Long existingDecisionId) {
            this(existingDecisionId, false);
        }

        private StubAlertGenerationService(Long existingDecisionId, boolean failOnExistingDecision) {
            super(null, null, null, null);
            this.existingDecisionId = existingDecisionId;
            this.failOnExistingDecision = failOnExistingDecision;
        }

        @Override
        public Map<String, Object> generate(AlertGenerationRunRequest request) {
            if (request.decisionId().equals(existingDecisionId)) {
                if (failOnExistingDecision) {
                    throw new IllegalStateException("sensitive SQL details must not leak");
                }
                return Map.of("action", "existing");
            }
            return Map.of("action", "created");
        }
    }
}
