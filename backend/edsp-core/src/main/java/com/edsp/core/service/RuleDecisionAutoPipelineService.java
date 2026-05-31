package com.edsp.core.service;

import com.edsp.core.dto.RuleEvaluationRunRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RuleDecisionAutoPipelineService {
    private static final String MODE = "new_standard_events_only";
    private static final String FAILURE_TYPE = "rule_decision_auto_evaluation_failed";
    private static final String FAILURE_MESSAGE = "Rule decision auto evaluation failed";

    private final RuleDecisionRunner runner;
    private final TransactionTemplate nestedTransactionTemplate;

    public RuleDecisionAutoPipelineService(
        PlatformTransactionManager transactionManager,
        RuleDecisionRunner runner
    ) {
        this.runner = runner;
        this.nestedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.nestedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public RuleDecisionAutoSummary evaluateNewStandardEvents(List<Long> standardEventIds) {
        if (standardEventIds == null || standardEventIds.isEmpty()) {
            return RuleDecisionAutoSummary.skipped();
        }

        var evaluatedStandardCount = 0;
        var decisionCount = 0;
        var matchedCount = 0;
        var notMatchedCount = 0;
        var skippedCount = 0;
        var errorCount = 0;
        var failedStandardCount = 0;

        for (var standardEventId : standardEventIds) {
            evaluatedStandardCount++;
            try {
                var result = nestedTransactionTemplate.execute(status -> runner.run(
                    new RuleEvaluationRunRequest(standardEventId, null, "system:plan-sync")
                ));
                if (result == null) {
                    throw new IllegalStateException("Rule decision runner returned no result");
                }
                decisionCount += intValue(result.get("evaluatedCount"));
                matchedCount += intValue(result.get("matchedCount"));
                notMatchedCount += intValue(result.get("notMatchedCount"));
                skippedCount += intValue(result.get("skippedCount"));
                errorCount += intValue(result.get("errorCount"));
            } catch (RuntimeException ex) {
                failedStandardCount++;
            }
        }

        var errorsByType = failedStandardCount == 0
            ? Map.<String, Integer>of()
            : Map.of(FAILURE_TYPE, failedStandardCount);
        var status = failedStandardCount > 0 || errorCount > 0 ? "warning" : "passed";
        return new RuleDecisionAutoSummary(
            MODE,
            status,
            evaluatedStandardCount,
            decisionCount,
            matchedCount,
            notMatchedCount,
            skippedCount,
            errorCount,
            failedStandardCount,
            errorsByType,
            failedStandardCount == 0 ? null : FAILURE_MESSAGE
        );
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public record RuleDecisionAutoSummary(
        String mode,
        String status,
        int evaluatedStandardCount,
        int decisionCount,
        int matchedCount,
        int notMatchedCount,
        int skippedCount,
        int errorCount,
        int failedStandardCount,
        Map<String, Integer> errorsByType,
        String errorMessage
    ) {
        public RuleDecisionAutoSummary {
            errorsByType = errorsByType == null ? Map.of() : Map.copyOf(errorsByType);
        }

        public static RuleDecisionAutoSummary skipped() {
            return new RuleDecisionAutoSummary(
                MODE,
                "skipped",
                0,
                0,
                0,
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
            report.put("evaluatedStandardCount", evaluatedStandardCount);
            report.put("decisionCount", decisionCount);
            report.put("matchedCount", matchedCount);
            report.put("notMatchedCount", notMatchedCount);
            report.put("skippedCount", skippedCount);
            report.put("errorCount", errorCount);
            report.put("failedStandardCount", failedStandardCount);
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
