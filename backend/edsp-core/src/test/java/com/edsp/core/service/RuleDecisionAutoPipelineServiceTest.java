package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.dto.RuleEvaluationRunRequest;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RuleDecisionAutoPipelineServiceTest {
    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:rule_decision_auto_pipeline_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists decision_probe");
        jdbcTemplate.execute("drop table if exists standard_probe");
        jdbcTemplate.execute("create table standard_probe(id bigint primary key)");
        jdbcTemplate.execute("create table decision_probe(standard_event_id bigint primary key)");
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @Test
    void emptyStandardEventListReturnsSkippedSummary() {
        var service = new RuleDecisionAutoPipelineService(transactionManager, new StubRuleDecisionRunner(jdbcTemplate, null));

        var summary = service.evaluateNewStandardEvents(List.of());

        assertEquals("skipped", summary.status());
        assertEquals(0, summary.evaluatedStandardCount());
        assertEquals(0, summary.decisionCount());
        assertEquals(Map.of(), summary.errorsByType());
    }

    @Test
    void successfulEvaluationsAggregateSafeDecisionCounts() {
        var service = new RuleDecisionAutoPipelineService(transactionManager, new StubRuleDecisionRunner(jdbcTemplate, null));

        var summary = service.evaluateNewStandardEvents(List.of(1L, 2L));

        assertEquals("passed", summary.status());
        assertEquals(2, summary.evaluatedStandardCount());
        assertEquals(4, summary.decisionCount());
        assertEquals(2, summary.matchedCount());
        assertEquals(2, summary.notMatchedCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(0, summary.errorCount());
        assertEquals(0, summary.failedStandardCount());
    }

    @Test
    void failedEvaluationRollsBackOnlyItsSavepointAndPreservesOuterWrites() {
        var service = new RuleDecisionAutoPipelineService(transactionManager, new StubRuleDecisionRunner(jdbcTemplate, 2L));
        var outer = new TransactionTemplate(transactionManager);

        var summary = outer.execute(status -> {
            jdbcTemplate.update("insert into standard_probe(id) values (1), (2)");
            return service.evaluateNewStandardEvents(List.of(1L, 2L));
        });

        assertEquals(2L, count("standard_probe"));
        assertEquals(1L, count("decision_probe"));
        assertEquals(1L, jdbcTemplate.queryForObject(
            "select standard_event_id from decision_probe",
            Long.class
        ));
        assertEquals("warning", summary.status());
        assertEquals(2, summary.evaluatedStandardCount());
        assertEquals(1, summary.failedStandardCount());
        assertEquals(Map.of("rule_decision_auto_evaluation_failed", 1), summary.errorsByType());
        assertEquals("Rule decision auto evaluation failed", summary.errorMessage());
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private static final class StubRuleDecisionRunner extends RuleDecisionRunner {
        private final JdbcTemplate jdbcTemplate;
        private final Long failingStandardEventId;

        private StubRuleDecisionRunner(JdbcTemplate jdbcTemplate, Long failingStandardEventId) {
            super(null, null, null, null, null);
            this.jdbcTemplate = jdbcTemplate;
            this.failingStandardEventId = failingStandardEventId;
        }

        @Override
        public Map<String, Object> run(RuleEvaluationRunRequest request) {
            jdbcTemplate.update(
                "insert into decision_probe(standard_event_id) values (?)",
                request.standardEventId()
            );
            if (request.standardEventId().equals(failingStandardEventId)) {
                throw new DataAccessResourceFailureException("sensitive SQL details must not leak");
            }
            return Map.of(
                "evaluatedCount", 2,
                "matchedCount", 1,
                "notMatchedCount", 1,
                "skippedCount", 0,
                "errorCount", 0
            );
        }
    }
}
