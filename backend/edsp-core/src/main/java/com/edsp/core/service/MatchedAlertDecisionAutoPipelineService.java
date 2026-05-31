package com.edsp.core.service;

import com.edsp.core.dto.AlertGenerationRunRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MatchedAlertDecisionAutoPipelineService {
    private static final String MODE = "new_standard_event_matched_decisions_only";
    private static final String FAILURE_TYPE = "alert_generation_auto_failed";
    private static final String FAILURE_MESSAGE = "Automatic alert generation failed";

    private final JdbcTemplate jdbcTemplate;
    private final AlertGenerationService alertGenerationService;
    private final TransactionTemplate nestedTransactionTemplate;

    public MatchedAlertDecisionAutoPipelineService(
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        AlertGenerationService alertGenerationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertGenerationService = alertGenerationService;
        this.nestedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.nestedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public AlertGenerationAutoSummary generateForNewStandardEvents(List<Long> standardEventIds) {
        if (standardEventIds == null || standardEventIds.isEmpty()) {
            return AlertGenerationAutoSummary.skipped();
        }

        var candidateDecisionIds = jdbcTemplate.queryForList("""
            select id
            from alert_decisions
            where standard_event_id in (%s)
              and rule_id is not null
              and decision = 'matched'
            order by id
            """.formatted(String.join(", ", Collections.nCopies(standardEventIds.size(), "?"))), Long.class,
            standardEventIds.toArray());
        if (candidateDecisionIds.isEmpty()) {
            return AlertGenerationAutoSummary.skipped();
        }

        var createdAlertCount = 0;
        var existingAlertCount = 0;
        var failedDecisionCount = 0;
        for (var decisionId : candidateDecisionIds) {
            try {
                var result = nestedTransactionTemplate.execute(status ->
                    alertGenerationService.generate(new AlertGenerationRunRequest(decisionId))
                );
                var action = result == null ? null : stringOrNull(result.get("action"));
                if ("created".equals(action)) {
                    createdAlertCount++;
                } else if ("existing".equals(action)) {
                    existingAlertCount++;
                } else {
                    failedDecisionCount++;
                }
            } catch (RuntimeException ex) {
                failedDecisionCount++;
            }
        }

        var status = failedDecisionCount > 0 ? "warning" : "passed";
        var errorsByType = failedDecisionCount == 0
            ? Map.<String, Integer>of()
            : Map.of(FAILURE_TYPE, failedDecisionCount);
        return new AlertGenerationAutoSummary(
            MODE,
            status,
            candidateDecisionIds.size(),
            createdAlertCount,
            existingAlertCount,
            failedDecisionCount,
            errorsByType,
            failedDecisionCount == 0 ? null : FAILURE_MESSAGE
        );
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record AlertGenerationAutoSummary(
        String mode,
        String status,
        int candidateDecisionCount,
        int createdAlertCount,
        int existingAlertCount,
        int failedDecisionCount,
        Map<String, Integer> errorsByType,
        String errorMessage
    ) {
        public AlertGenerationAutoSummary {
            errorsByType = errorsByType == null ? Map.of() : Map.copyOf(errorsByType);
        }

        public static AlertGenerationAutoSummary skipped() {
            return new AlertGenerationAutoSummary(
                MODE,
                "skipped",
                0,
                0,
                0,
                0,
                Map.of(),
                null
            );
        }

        public Map<String, Object> toReportMap() {
            var report = new LinkedHashMap<String, Object>();
            report.put("mode", mode);
            report.put("status", status);
            report.put("candidateDecisionCount", candidateDecisionCount);
            report.put("createdAlertCount", createdAlertCount);
            report.put("existingAlertCount", existingAlertCount);
            report.put("failedDecisionCount", failedDecisionCount);
            if (!errorsByType.isEmpty()) {
                report.put("errorsByType", errorsByType);
            }
            if (errorMessage != null) {
                report.put("errorMessage", errorMessage);
            }
            return report;
        }
    }
}
