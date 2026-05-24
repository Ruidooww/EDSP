package com.edsp.alert.service;

import com.edsp.alert.dto.IngestAlertRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RuleExecutionService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RuleExecutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> execute(long alertId, IngestAlertRequest request, String severity, Timestamp occurredAt) {
        var detail = normalizedDetail(request);
        var matchedRules = new ArrayList<Map<String, Object>>();
        var notifications = new ArrayList<Map<String, Object>>();

        var rules = jdbcTemplate.queryForList("""
            select id, name, event_type, severity, expression
            from rules
            where enabled = true
            order by id
            """);

        for (var rule : rules) {
            var expression = parseExpression(rule.get("expression"));
            if (!matchesRule(rule, expression, request, detail, occurredAt)) {
                continue;
            }

            var ruleMatch = new LinkedHashMap<String, Object>();
            ruleMatch.put("id", rule.get("id"));
            ruleMatch.put("name", rule.get("name"));
            ruleMatch.put("eventType", rule.get("event_type"));
            matchedRules.add(ruleMatch);

            var action = mapValue(expression.get("action"));
            if (Boolean.TRUE.equals(action.get("notify"))) {
                notifications.add(Map.of(
                    "status", "skipped",
                    "message", "automatic_notification_disabled",
                    "channelIds", channelIds(action.get("channelIds"))
                ));
            }
        }

        if (!matchedRules.isEmpty()) {
            var firstRuleId = ((Number) matchedRules.get(0).get("id")).longValue();
            jdbcTemplate.update("update alerts set rule_id = ?, updated_at = now() where id = ?", firstRuleId, alertId);
            saveAudit(alertId, matchedRules, notifications);
        }

        return Map.of(
            "matched", matchedRules.size(),
            "rules", matchedRules,
            "notifications", notifications
        );
    }

    private boolean matchesRule(
        Map<String, Object> rule,
        Map<String, Object> expression,
        IngestAlertRequest request,
        Map<String, Object> detail,
        Timestamp occurredAt
    ) {
        if (!matchesEventType(String.valueOf(rule.get("event_type")), request.alertType(), expression)) {
            return false;
        }
        if (!matchesTimeWindow(String.valueOf(expression.getOrDefault("timeWindow", "all_day")), occurredAt)) {
            return false;
        }
        if (!matchesThreshold(mapValue(expression.get("threshold")), detail)) {
            return false;
        }
        return matchesScope(mapValue(expression.get("scope")), detail);
    }

    private boolean matchesEventType(String ruleEventType, String alertType, Map<String, Object> expression) {
        var normalizedRuleType = normalize(ruleEventType);
        var normalizedAlertType = normalize(alertType);
        if (normalizedRuleType.equals(normalizedAlertType)) {
            return true;
        }
        if (normalizedRuleType.equals(eventTypeAlias(normalizedAlertType))) {
            return true;
        }
        var scenario = normalize(String.valueOf(expression.getOrDefault("scenario", "")));
        return normalizedRuleType.equals(scenarioEventType(scenario));
    }

    private boolean matchesTimeWindow(String timeWindow, Timestamp occurredAt) {
        var instant = occurredAt == null ? Instant.now() : occurredAt.toInstant();
        var now = Instant.now();
        return switch (normalize(timeWindow)) {
            case "work_hours" -> {
                var time = LocalTime.ofInstant(instant, DEFAULT_ZONE);
                yield !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(18, 0));
            }
            case "after_hours" -> {
                var time = LocalTime.ofInstant(instant, DEFAULT_ZONE);
                yield time.isBefore(LocalTime.of(9, 0)) || !time.isBefore(LocalTime.of(18, 0));
            }
            case "last_1h" -> !instant.isBefore(now.minusSeconds(3600));
            case "last_24h" -> !instant.isBefore(now.minusSeconds(86400));
            default -> true;
        };
    }

    private boolean matchesThreshold(Map<String, Object> threshold, Map<String, Object> detail) {
        var metric = String.valueOf(threshold.getOrDefault("metric", ""));
        if (metric.isBlank() || "legacy".equals(metric)) {
            return true;
        }
        var expected = numberValue(threshold.get("value"));
        if (expected <= 0) {
            return true;
        }
        var actual = numberValue(detail.get(metric));
        return actual >= expected;
    }

    private boolean matchesScope(Map<String, Object> scope, Map<String, Object> detail) {
        var subjectScope = normalize(String.valueOf(scope.getOrDefault("subject", "all_users")));
        var assetScope = normalize(String.valueOf(scope.getOrDefault("asset", "all_assets")));
        return matchesOptionalScope(subjectScope, detail.get("subjectScope"), "all_users")
            && matchesOptionalScope(assetScope, detail.get("assetScope"), "all_assets");
    }

    private boolean matchesOptionalScope(String ruleScope, Object actualScope, String allValue) {
        if (ruleScope.isBlank() || allValue.equals(ruleScope) || actualScope == null) {
            return true;
        }
        return ruleScope.equals(normalize(String.valueOf(actualScope)));
    }

    private void saveAudit(long alertId, List<Map<String, Object>> matchedRules, List<Map<String, Object>> notifications) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("alertId", alertId);
        detail.put("matchedRules", matchedRules);
        detail.put("notifications", notifications);
        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, "system", "rule.alert.executed", "alert", String.valueOf(alertId), toJson(detail));
    }

    private Map<String, Object> normalizedDetail(IngestAlertRequest request) {
        var detail = new LinkedHashMap<String, Object>();
        if (request.detail() != null) {
            detail.putAll(request.detail());
        }
        detail.putIfAbsent("sourceSystem", request.sourceSystem());
        detail.putIfAbsent("externalId", request.externalId());
        detail.putIfAbsent("alertType", request.alertType());
        detail.putIfAbsent("actor", request.actor());
        detail.putIfAbsent("asset", request.asset());
        detail.putIfAbsent("policyName", request.policyName());
        return detail;
    }

    private Map<String, Object> parseExpression(Object expression) {
        if (expression == null) {
            return Map.of();
        }
        var value = String.valueOf(expression);
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return parseLegacyExpression(value);
        }
    }

    private Map<String, Object> parseLegacyExpression(String expression) {
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", "legacy");
        result.put("scenario", extractQuoted(expression, "scenario"));
        result.put("timeWindow", extractQuoted(expression, "time_window"));
        var threshold = new LinkedHashMap<String, Object>();
        threshold.put("metric", "legacy");
        threshold.put("value", 0);
        result.put("threshold", threshold);
        result.put("action", Map.of("notify", false, "channelIds", List.of()));
        return result;
    }

    private String extractQuoted(String expression, String key) {
        var marker = key + " == \"";
        var start = expression.indexOf(marker);
        if (start < 0) {
            return "";
        }
        var valueStart = start + marker.length();
        var valueEnd = expression.indexOf('"', valueStart);
        return valueEnd < 0 ? "" : expression.substring(valueStart, valueEnd);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private List<Long> channelIds(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(item -> item instanceof Number || item instanceof String)
            .map(item -> item instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(item)))
            .toList();
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String eventTypeAlias(String alertType) {
        return switch (alertType) {
            case "sensitive_field_access", "sensitive_data_access", "data_access" -> "data_access";
            case "file_leakage", "file_operation" -> "file_operation";
            case "large_file_transfer", "file_transfer" -> "file_transfer";
            case "removable_storage", "device_operation" -> "device_operation";
            case "abnormal_login", "account_activity" -> "account_activity";
            default -> alertType;
        };
    }

    private String scenarioEventType(String scenario) {
        return switch (scenario) {
            case "file_leakage" -> "file_operation";
            case "large_file_transfer" -> "file_transfer";
            case "removable_storage" -> "device_operation";
            case "sensitive_data_access" -> "data_access";
            case "abnormal_login" -> "account_activity";
            default -> scenario;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
