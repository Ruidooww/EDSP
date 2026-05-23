package com.edsp.core.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TemplateMatcherService {
    private static final List<String> MAIN_TEMPLATES = List.of("alert_table", "log_table");
    private static final List<String> CORE_SIGNALS = List.of(
        "occurred_at",
        "severity",
        "actor",
        "asset_ref",
        "title",
        "external_id",
        "policy_name",
        "result",
        "subject_ref"
    );
    private static final List<String> AUXILIARY_TEMPLATES = List.of(
        "user_table",
        "asset_table",
        "policy_table",
        "detail_table"
    );

    public TemplateMatch match(String tableName, String category, List<String> fieldNames) {
        var normalizedCategory = normalize(category);
        if (MAIN_TEMPLATES.contains(normalizedCategory)) {
            return templateMatch(normalizedCategory, 95, true, "category", fieldNames);
        }
        if (AUXILIARY_TEMPLATES.contains(normalizedCategory)) {
            return templateMatch(normalizedCategory, 95, false, "category", fieldNames);
        }

        var tokens = tokens(tableName, normalizedCategory, fieldNames);
        if (containsAny(tokens, "log", "audit", "trace", "operation", "access")) {
            return templateMatch("log_table", 78, true, "name_or_fields", fieldNames);
        }
        if (containsAny(tokens, "alert", "alarm", "incident", "risk", "event")) {
            return templateMatch("alert_table", 82, true, "name_or_fields", fieldNames);
        }
        if (containsAny(tokens, "user", "account", "employee", "operator")) {
            return templateMatch("user_table", 74, false, "name_or_fields", fieldNames);
        }
        if (containsAny(tokens, "asset", "host", "device", "terminal", "cmdb", "ip")) {
            return templateMatch("asset_table", 74, false, "name_or_fields", fieldNames);
        }
        if (containsAny(tokens, "policy", "rule", "strategy")) {
            return templateMatch("policy_table", 72, false, "name_or_fields", fieldNames);
        }
        if (containsAny(tokens, "detail", "extend", "extra")) {
            return templateMatch("detail_table", 70, false, "name_or_fields", fieldNames);
        }
        return templateMatch("unknown", 35, false, "unmatched", fieldNames);
    }

    private TemplateMatch templateMatch(
        String templateKey,
        int confidence,
        boolean mainPlanCandidate,
        String matchedBy,
        List<String> fieldNames
    ) {
        var matchedSignals = matchedSignals(fieldNames);
        var missingSignals = missingSignals(templateKey, matchedSignals);
        return new TemplateMatch(
            templateKey,
            displayName(templateKey),
            confidence,
            mainPlanCandidate,
            matchedBy,
            matchedSignals,
            missingSignals,
            mainPlanCandidate
                ? "table is suitable as a primary ingestion source"
                : "table is auxiliary metadata and should not create a primary ingestion plan"
        );
    }

    private List<String> matchedSignals(List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return List.of();
        }
        var signals = new LinkedHashSet<String>();
        for (var fieldName : fieldNames) {
            var tokens = tokens(fieldName, null, null);
            if (containsAny(tokens, "time", "date", "occurred", "created", "create", "eventtime", "timestamp")) {
                signals.add("occurred_at");
            }
            if (containsAny(tokens, "severity", "level", "priority", "risk")) {
                signals.add("severity");
            }
            if (containsAny(tokens, "user", "account", "actor", "operator", "employee", "login")) {
                signals.add("actor");
            }
            if (containsAny(tokens, "host", "asset", "device", "terminal", "ip", "server")) {
                signals.add("asset_ref");
            }
            if (containsAny(tokens, "title", "message", "description", "desc", "summary")
                || (containsAny(tokens, "event", "alert", "alarm", "incident") && containsAny(tokens, "name"))) {
                signals.add("title");
            }
            if (containsAny(tokens, "id", "uuid", "guid", "external", "eventid", "logid", "alertid")) {
                signals.add("external_id");
            }
            if (containsAny(tokens, "policy", "rule", "strategy")) {
                signals.add("policy_name");
            }
            if (containsAny(tokens, "result", "status", "outcome", "action")) {
                signals.add("result");
            }
            if (containsAny(tokens, "subject", "target", "object", "resource", "file")) {
                signals.add("subject_ref");
            }
        }
        return List.copyOf(signals);
    }

    private List<String> missingSignals(String templateKey, List<String> matchedSignals) {
        if (!MAIN_TEMPLATES.contains(templateKey)) {
            return List.of();
        }
        var matched = Set.copyOf(matchedSignals);
        return CORE_SIGNALS.stream()
            .filter(signal -> !matched.contains(signal))
            .toList();
    }

    private Set<String> tokens(String tableName, String category, List<String> fieldNames) {
        var tokens = new LinkedHashSet<String>();
        addTokens(tokens, tableName);
        addTokens(tokens, category);
        if (fieldNames != null) {
            fieldNames.forEach(fieldName -> addTokens(tokens, fieldName));
        }
        return tokens;
    }

    private void addTokens(Set<String> tokens, String value) {
        if (value == null) {
            return;
        }
        for (var token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
    }

    private boolean containsAny(Set<String> sourceTokens, String... candidateTokens) {
        for (var candidate : candidateTokens) {
            if (sourceTokens.contains(candidate)
                || sourceTokens.contains(candidate + "s")
                || sourceTokens.contains(candidate + "es")
                || sourceTokens.contains(pluralY(candidate))) {
                return true;
            }
        }
        return false;
    }

    private String pluralY(String token) {
        if (!token.endsWith("y") || token.length() < 2) {
            return token;
        }
        return token.substring(0, token.length() - 1) + "ies";
    }

    private String displayName(String templateKey) {
        return switch (templateKey) {
            case "alert_table" -> "Alert Table";
            case "log_table" -> "Log Table";
            case "user_table" -> "User Table";
            case "asset_table" -> "Asset Table";
            case "policy_table" -> "Policy Table";
            case "detail_table" -> "Detail Table";
            default -> "Unknown Table";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record TemplateMatch(
        String templateKey,
        String templateName,
        int confidence,
        boolean mainPlanCandidate,
        String matchedBy,
        List<String> matchedSignals,
        List<String> missingSignals,
        String reason
    ) {
        public TemplateMatch(
            String templateKey,
            String templateName,
            int confidence,
            boolean mainPlanCandidate,
            String matchedBy
        ) {
            this(
                templateKey,
                templateName,
                confidence,
                mainPlanCandidate,
                matchedBy,
                List.of(matchedBy),
                List.of(),
                mainPlanCandidate
                    ? "table is suitable as a primary ingestion source"
                    : "table is auxiliary metadata and should not create a primary ingestion plan"
            );
        }
    }
}
