package com.edsp.transform.standardevent.normalize;

public final class RiskScoreCalculator {
    public Integer riskScore(String severity) {
        return switch (severity) {
            case "critical" -> 95;
            case "high" -> 80;
            case "medium" -> 55;
            case "low" -> 25;
            default -> 10;
        };
    }
}
