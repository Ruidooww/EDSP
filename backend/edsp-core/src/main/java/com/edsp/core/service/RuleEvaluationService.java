package com.edsp.core.service;

import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RuleEvaluationService {
    private static final List<String> SUPPORTED_OPERATORS = List.of(">=", ">", "<=", "<", "==");

    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;

    public RuleEvaluationService(ObjectMapper objectMapper, CoreRequestSupport support) {
        this.objectMapper = objectMapper;
        this.support = support;
    }

    public EvaluationResult evaluate(StandardEventContext event, RuleRecord rule) {
        var ruleEventType = support.stringOrNull(rule.eventType());
        if (ruleEventType != null && !"*".equals(ruleEventType)
            && !ruleEventType.equalsIgnoreCase(support.stringOrDefault(event.eventType(), ""))) {
            return skipped("event_type_mismatch", Map.of(
                "eventType", support.stringOrDefault(event.eventType(), ""),
                "ruleEventType", ruleEventType
            ));
        }

        RuleConfig config;
        try {
            config = parseRuleConfig(rule.expression());
        } catch (RuntimeException ex) {
            return error("expression_malformed", Map.of("message", ex.getMessage()));
        }

        var timeWindow = support.stringOrDefault(config.timeWindow(), "all_day");
        if (!"all_day".equalsIgnoreCase(timeWindow)) {
            return skipped("time_window_unsupported", Map.of("timeWindow", timeWindow));
        }

        var minSeverity = support.stringOrNull(config.minSeverity());
        if (minSeverity != null && severityRank(event.severity()) < severityRank(minSeverity)) {
            return notMatched("min_severity_not_met", Map.of(
                "eventSeverity", support.stringOrDefault(event.severity(), ""),
                "minSeverity", minSeverity
            ));
        }

        var threshold = config.threshold();
        if (threshold == null || support.stringOrNull(threshold.metric()) == null
            || support.stringOrNull(threshold.operator()) == null || threshold.value() == null) {
            return error("threshold_malformed", Map.of("message", "threshold.metric/operator/value are required"));
        }
        var operator = threshold.operator().trim();
        if (!SUPPORTED_OPERATORS.contains(operator)) {
            return error("operator_unsupported", Map.of("operator", operator));
        }

        var metric = threshold.metric().trim();
        var metricValue = metricValue(event, metric);
        if (metricValue == null) {
            return skipped("metric_missing", Map.of("metric", metric));
        }
        var numericValue = numeric(metricValue);
        if (numericValue == null) {
            return skipped("metric_non_numeric", Map.of("metric", metric, "value", String.valueOf(metricValue)));
        }
        var thresholdValue = numeric(threshold.value());
        if (thresholdValue == null) {
            return error("threshold_value_non_numeric", Map.of(
                "metric", metric,
                "thresholdValue", String.valueOf(threshold.value())
            ));
        }

        var detail = new LinkedHashMap<String, Object>();
        detail.put("metric", metric);
        detail.put("operator", operator);
        detail.put("metricValue", numericValue);
        detail.put("thresholdValue", thresholdValue);
        if (compare(numericValue, thresholdValue, operator)) {
            return matched("threshold_matched", detail);
        }
        return notMatched("threshold_not_matched", detail);
    }

    private RuleConfig parseRuleConfig(String expression) {
        try {
            var node = objectMapper.readTree(support.stringOrDefault(expression, "{}"));
            var mode = node.path("mode").asText("structured_config");
            if (!"structured_config".equals(mode)) {
                throw new IllegalArgumentException("expression.mode must be structured_config");
            }
            var thresholdNode = node.path("threshold");
            ThresholdConfig threshold = null;
            if (!thresholdNode.isMissingNode() && !thresholdNode.isNull()) {
                threshold = new ThresholdConfig(
                    thresholdNode.path("metric").asText(null),
                    thresholdNode.path("operator").asText(null),
                    thresholdNode.path("value").isMissingNode() || thresholdNode.path("value").isNull()
                        ? null
                        : objectMapper.convertValue(thresholdNode.path("value"), Object.class)
                );
            }
            return new RuleConfig(
                node.path("timeWindow").asText("all_day"),
                node.path("minSeverity").isMissingNode() || node.path("minSeverity").isNull()
                    ? null
                    : node.path("minSeverity").asText(),
                threshold
            );
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid structured rule expression", ex);
        }
    }

    private Object metricValue(StandardEventContext event, String metric) {
        return switch (metric) {
            case "riskScore", "risk_score" -> {
                var nested = nestedMetric(event, metric);
                yield nested == null ? event.riskScore() : nested;
            }
            case "severity" -> event.severity();
            case "eventType", "event_type" -> event.eventType();
            case "actor" -> event.actor();
            case "assetRef", "asset_ref" -> event.assetRef();
            case "subjectRef", "subject_ref" -> event.subjectRef();
            case "action" -> event.action();
            case "result" -> event.result();
            default -> nestedMetric(event, metric);
        };
    }

    private Object nestedMetric(StandardEventContext event, String metric) {
        var normalized = parseJson(event.normalizedJson());
        var mapped = normalized.get("mapped");
        if (mapped instanceof Map<?, ?> mappedValues && mappedValues.containsKey(metric)) {
            return mappedValues.get(metric);
        }
        if (normalized.containsKey(metric)) {
            return normalized.get(metric);
        }
        var extra = parseJson(event.extraJson());
        return extra.get(metric);
    }

    private Map<String, Object> parseJson(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        try {
            var node = value instanceof byte[] bytes
                ? objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8))
                : objectMapper.readTree(String.valueOf(value));
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private Double numeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        var text = support.stringOrNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean compare(double value, double threshold, String operator) {
        return switch (operator) {
            case ">=" -> value >= threshold;
            case ">" -> value > threshold;
            case "<=" -> value <= threshold;
            case "<" -> value < threshold;
            case "==" -> Double.compare(value, threshold) == 0;
            default -> false;
        };
    }

    private int severityRank(String severity) {
        return switch (support.stringOrDefault(severity, "info").toLowerCase(Locale.ROOT)) {
            case "low" -> 1;
            case "medium" -> 2;
            case "high" -> 3;
            case "critical" -> 4;
            default -> 0;
        };
    }

    private EvaluationResult matched(String reason, Map<String, Object> detail) {
        return new EvaluationResult("matched", reason, detail);
    }

    private EvaluationResult notMatched(String reason, Map<String, Object> detail) {
        return new EvaluationResult("not_matched", reason, detail);
    }

    private EvaluationResult skipped(String reason, Map<String, Object> detail) {
        return new EvaluationResult("skipped", reason, detail);
    }

    private EvaluationResult error(String reason, Map<String, Object> detail) {
        return new EvaluationResult("error", reason, detail);
    }

    public record EvaluationResult(String decision, String reason, Map<String, Object> detail) {
    }

    public record StandardEventContext(
        Long id,
        String eventType,
        String actor,
        String assetRef,
        String subjectRef,
        String action,
        String result,
        String severity,
        Integer riskScore,
        Object normalizedJson,
        Object extraJson
    ) {
    }

    public record RuleRecord(
        Long id,
        String name,
        String eventType,
        String severity,
        String expression,
        Boolean enabled
    ) {
    }

    private record RuleConfig(String timeWindow, String minSeverity, ThresholdConfig threshold) {
    }

    private record ThresholdConfig(String metric, String operator, Object value) {
    }
}
