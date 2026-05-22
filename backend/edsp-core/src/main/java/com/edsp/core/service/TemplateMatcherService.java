package com.edsp.core.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TemplateMatcherService {
    private static final List<String> MAIN_TEMPLATES = List.of("alert_table", "log_table");
    private static final List<String> AUXILIARY_TEMPLATES = List.of(
        "user_table",
        "asset_table",
        "policy_table",
        "detail_table"
    );

    public TemplateMatch match(String tableName, String category, List<String> fieldNames) {
        var normalizedCategory = normalize(category);
        if (MAIN_TEMPLATES.contains(normalizedCategory)) {
            return new TemplateMatch(normalizedCategory, displayName(normalizedCategory), 95, true, "category");
        }
        if (AUXILIARY_TEMPLATES.contains(normalizedCategory)) {
            return new TemplateMatch(normalizedCategory, displayName(normalizedCategory), 95, false, "category");
        }

        var tokens = tokens(tableName, normalizedCategory, fieldNames);
        if (containsAny(tokens, "log", "audit", "trace", "operation", "access")) {
            return new TemplateMatch("log_table", "Log Table", 78, true, "name_or_fields");
        }
        if (containsAny(tokens, "alert", "alarm", "incident", "risk", "event")) {
            return new TemplateMatch("alert_table", "Alert Table", 82, true, "name_or_fields");
        }
        if (containsAny(tokens, "user", "account", "employee", "operator")) {
            return new TemplateMatch("user_table", "User Table", 74, false, "name_or_fields");
        }
        if (containsAny(tokens, "asset", "host", "device", "terminal", "cmdb", "ip")) {
            return new TemplateMatch("asset_table", "Asset Table", 74, false, "name_or_fields");
        }
        if (containsAny(tokens, "policy", "rule", "strategy")) {
            return new TemplateMatch("policy_table", "Policy Table", 72, false, "name_or_fields");
        }
        if (containsAny(tokens, "detail", "extend", "extra")) {
            return new TemplateMatch("detail_table", "Detail Table", 70, false, "name_or_fields");
        }
        return new TemplateMatch("unknown", "Unknown Table", 35, false, "unmatched");
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
