package com.edsp.core.dto;

public record RuleEvaluationRunRequest(
    Long standardEventId,
    Long ruleId,
    String operatorName
) {
}
