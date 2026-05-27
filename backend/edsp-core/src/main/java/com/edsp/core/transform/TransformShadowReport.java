package com.edsp.core.transform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TransformShadowReport(
    boolean enabled,
    int attempted,
    int matched,
    int mismatched,
    int unavailable,
    List<Map<String, Object>> mismatches
) {
    public TransformShadowReport {
        mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
    }

    public static TransformShadowReport disabled() {
        return new TransformShadowReport(false, 0, 0, 0, 0, List.of());
    }

    public static TransformShadowReport enabled(
        int attempted,
        int matched,
        int mismatched,
        int unavailable,
        List<Map<String, Object>> mismatches
    ) {
        return new TransformShadowReport(true, attempted, matched, mismatched, unavailable, mismatches);
    }

    public static TransformShadowReport unavailable(int attempted) {
        return new TransformShadowReport(true, attempted, 0, 0, attempted, List.of());
    }

    public Map<String, Object> toReportMap() {
        var report = new LinkedHashMap<String, Object>();
        report.put("enabled", enabled);
        report.put("attempted", attempted);
        report.put("matched", matched);
        report.put("mismatched", mismatched);
        report.put("unavailable", unavailable);
        if (!mismatches.isEmpty()) {
            report.put("mismatches", mismatches);
        }
        return report;
    }
}
