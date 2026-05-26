package com.edsp.transform.standardevent.normalize;

import java.util.Locale;
import java.util.Map;

public final class SeverityNormalizer {
    private static final Map<String, String> NORMALIZED_SEVERITIES = Map.ofEntries(
        Map.entry("critical", "critical"),
        Map.entry("1", "critical"),
        Map.entry("high", "high"),
        Map.entry("2", "high"),
        Map.entry("medium", "medium"),
        Map.entry("warning", "medium"),
        Map.entry("3", "medium"),
        Map.entry("low", "low"),
        Map.entry("4", "low"),
        Map.entry("info", "info")
    );

    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        return NORMALIZED_SEVERITIES.get(value.toLowerCase(Locale.ROOT));
    }
}
