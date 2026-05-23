package com.edsp.core.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String SOURCE_FIELD_NAME = "field_name";
    private static final Map<String, Set<String>> FIELD_ALIASES = Map.of(
        "occurred_at", Set.of("create_time", "created_at", "event_time", "alert_time", "alarm_time", "告警时间"),
        "severity", Set.of("severity", "risk_level", "alert_level", "alarm_level", "告警级别"),
        "actor", Set.of("user_account", "account", "actor", "operator", "login_name", "账号"),
        "asset_ref", Set.of("host_name", "hostname", "host", "ip", "ip_address", "asset_id", "device_id", "主机"),
        "title", Set.of("title", "message", "description", "desc", "summary", "event_name", "alert_name", "alarm_name", "incident_name"),
        "external_id", Set.of("external_id", "event_id", "log_id", "alert_id", "raw_event_id", "uuid", "guid"),
        "policy_name", Set.of("policy_name", "policy", "rule_name", "strategy", "策略"),
        "result", Set.of("result", "process_result", "handling_result", "outcome", "处理结果"),
        "subject_ref", Set.of("subject", "target", "target_object", "object", "resource", "file", "目标对象")
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
        var signalEvidence = signalEvidence(fieldNames);
        var matchedSignals = matchedSignals(signalEvidence);
        var missingSignals = missingSignals(templateKey, matchedSignals);
        return new TemplateMatch(
            templateKey,
            displayName(templateKey),
            confidence,
            mainPlanCandidate,
            matchedBy,
            matchedSignals,
            missingSignals,
            signalEvidence,
            mainPlanCandidate
                ? "table is suitable as a primary ingestion source"
                : "table is auxiliary metadata and should not create a primary ingestion plan"
        );
    }

    private List<String> matchedSignals(List<SignalEvidence> signalEvidence) {
        return signalEvidence.stream()
            .map(SignalEvidence::signal)
            .toList();
    }

    private List<SignalEvidence> signalEvidence(List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return List.of();
        }
        var evidence = new LinkedHashMap<String, SignalEvidenceBuilder>();
        for (var fieldName : fieldNames) {
            var matches = fieldSignalMatches(fieldName);
            for (var match : matches) {
                evidence.computeIfAbsent(match.signal(), SignalEvidenceBuilder::new)
                    .add(fieldName);
            }
        }
        return CORE_SIGNALS.stream()
            .filter(evidence::containsKey)
            .map(signal -> evidence.get(signal).build())
            .toList();
    }

    private List<FieldSignalMatch> fieldSignalMatches(String fieldName) {
        var field = fieldTokens(fieldName);
        var matches = new ArrayList<FieldSignalMatch>();
        for (var signal : CORE_SIGNALS) {
            if (isAlias(signal, field)) {
                matches.add(new FieldSignalMatch(signal, SOURCE_FIELD_NAME));
            }
        }
        addTokenMatch(matches, "occurred_at", isOccurredAtTokenMatch(field));
        addTokenMatch(matches, "severity", isSeverityTokenMatch(field));
        addTokenMatch(matches, "actor", isActorTokenMatch(field));
        addTokenMatch(matches, "asset_ref", isAssetRefTokenMatch(field));
        addTokenMatch(matches, "title", isTitleTokenMatch(field));
        addTokenMatch(matches, "external_id", isExternalIdTokenMatch(field));
        addTokenMatch(matches, "policy_name", isPolicyNameTokenMatch(field));
        addTokenMatch(matches, "result", isResultTokenMatch(field));
        addTokenMatch(matches, "subject_ref", isSubjectRefTokenMatch(field));
        return matches;
    }

    private void addTokenMatch(List<FieldSignalMatch> matches, String signal, boolean matched) {
        if (!matched || matches.stream().anyMatch(match -> signal.equals(match.signal()))) {
            return;
        }
        matches.add(new FieldSignalMatch(signal, SOURCE_FIELD_NAME));
    }

    private boolean isAlias(String signal, FieldTokens field) {
        var aliases = FIELD_ALIASES.getOrDefault(signal, Set.of());
        return aliases.contains(field.normalized()) || aliases.contains(field.compact());
    }

    private boolean isOccurredAtTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "timestamp", "eventtime", "alerttime", "alarmtime")
            || (hasAny(field.tokens(), "time", "date")
                && hasAny(field.tokens(), "event", "alert", "alarm", "occurred", "create", "created"));
    }

    private boolean isSeverityTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "severity", "priority")
            || (hasAny(field.tokens(), "risk", "alert", "alarm") && hasAny(field.tokens(), "level"));
    }

    private boolean isActorTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "user", "account", "actor", "operator", "employee", "login");
    }

    private boolean isAssetRefTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "host", "asset", "device", "terminal", "ip", "server");
    }

    private boolean isTitleTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "title", "message", "description", "desc", "summary")
            || (hasAny(field.tokens(), "event", "alert", "alarm", "incident") && hasAny(field.tokens(), "name"));
    }

    private boolean isExternalIdTokenMatch(FieldTokens field) {
        if ("id".equals(field.normalized()) || "id".equals(field.compact())) {
            return false;
        }
        return hasAny(field.tokens(), "eventid", "logid", "alertid", "externalid")
            || (hasAny(field.tokens(), "id", "uuid", "guid")
                && hasAny(field.tokens(), "external", "event", "log", "alert"));
    }

    private boolean isPolicyNameTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "policy", "rule", "strategy");
    }

    private boolean isResultTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "result", "status", "outcome", "action");
    }

    private boolean isSubjectRefTokenMatch(FieldTokens field) {
        return hasAny(field.tokens(), "subject", "target", "object", "resource", "file");
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
        var normalized = normalizeIdentifier(value);
        for (var token : normalized.split("_+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        var compact = normalized.replace("_", "");
        if (!compact.isBlank()) {
            tokens.add(compact);
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

    private boolean hasAny(Set<String> sourceTokens, String... candidateTokens) {
        return containsAny(sourceTokens, candidateTokens);
    }

    private FieldTokens fieldTokens(String fieldName) {
        var normalized = normalizeIdentifier(fieldName);
        var tokens = new LinkedHashSet<String>();
        addTokens(tokens, fieldName);
        return new FieldTokens(fieldName, normalized, normalized.replace("_", ""), tokens);
    }

    private String normalizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        var camelSeparated = value.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return camelSeparated.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\p{IsHan}]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
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

    private record FieldTokens(String original, String normalized, String compact, Set<String> tokens) {
    }

    private record FieldSignalMatch(String signal, String source) {
    }

    private static final class SignalEvidenceBuilder {
        private final String signal;
        private final LinkedHashSet<String> sourceFields = new LinkedHashSet<>();
        private String source = SOURCE_FIELD_NAME;

        private SignalEvidenceBuilder(String signal) {
            this.signal = signal;
        }

        private void add(String sourceField) {
            sourceFields.add(sourceField);
        }

        private SignalEvidence build() {
            return new SignalEvidence(signal, List.copyOf(sourceFields), source);
        }
    }

    public record SignalEvidence(String signal, List<String> sourceFields, String source) {
    }

    public record TemplateMatch(
        String templateKey,
        String templateName,
        int confidence,
        boolean mainPlanCandidate,
        String matchedBy,
        List<String> matchedSignals,
        List<String> missingSignals,
        List<SignalEvidence> signalEvidence,
        String reason
    ) {
        public TemplateMatch(
            String templateKey,
            String templateName,
            int confidence,
            boolean mainPlanCandidate,
            String matchedBy,
            List<String> matchedSignals,
            List<String> missingSignals,
            String reason
        ) {
            this(
                templateKey,
                templateName,
                confidence,
                mainPlanCandidate,
                matchedBy,
                matchedSignals,
                missingSignals,
                List.of(),
                reason
            );
        }

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
                List.of(),
                mainPlanCandidate
                    ? "table is suitable as a primary ingestion source"
                    : "table is auxiliary metadata and should not create a primary ingestion plan"
            );
        }
    }
}
