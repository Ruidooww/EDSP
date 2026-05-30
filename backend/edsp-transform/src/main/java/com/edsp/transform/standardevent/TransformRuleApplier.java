package com.edsp.transform.standardevent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TransformRuleApplier {
    static final String WARNING_UNSUPPORTED = "transform_rule_unsupported";
    static final String WARNING_INVALID = "transform_rule_invalid";
    static final String WARNING_MISMATCH = "transform_rule_mismatch";
    static final String WARNING_VALUE_MAP_MISS = "transform_rule_value_map_miss";
    static final String WARNING_VALUE_MAP_INVALID_PAYLOAD = "transform_rule_value_map_invalid_payload";
    static final String WARNING_VALUE_MAP_INVALID_VALUES = "transform_rule_value_map_invalid_values";

    private static final String DEFAULT_IF_BLANK_PREFIX = "defaultIfBlank:";
    private static final int VALUE_MAP_MAX_ENTRIES = 200;
    private static final int VALUE_MAP_MAX_KEY_LENGTH = 200;
    private static final int VALUE_MAP_MAX_VALUE_LENGTH = 500;

    RuleApplication apply(Object value, String transformRule, String sourceField) {
        return apply(value, transformRule, sourceField, Map.of());
    }

    RuleApplication apply(Object value, String transformRule, String sourceField, Map<String, Object> transformRulePayload) {
        if (transformRule == null || transformRule.trim().isEmpty()) {
            return new RuleApplication(value, List.of());
        }
        var ruleWithLeadingWhitespaceRemoved = transformRule.stripLeading();
        if (startsWithIgnoreCase(ruleWithLeadingWhitespaceRemoved, DEFAULT_IF_BLANK_PREFIX)) {
            return new RuleApplication(
                defaultIfBlank(value, ruleWithLeadingWhitespaceRemoved.substring(DEFAULT_IF_BLANK_PREFIX.length())),
                List.of()
            );
        }
        var rule = transformRule.trim();
        if ("defaultIfBlank".equalsIgnoreCase(rule)) {
            return warning(value, WARNING_INVALID);
        }
        if (isSimpleUnary(rule)) {
            return new RuleApplication(applyUnary(value, rule), List.of());
        }
        if ("valueMap".equalsIgnoreCase(rule)) {
            return applyValueMap(value, transformRulePayload);
        }
        if (rule.contains("(") || rule.contains(")")) {
            return applyLegacyUnary(value, rule, sourceField);
        }
        return warning(value, WARNING_UNSUPPORTED);
    }

    private RuleApplication applyValueMap(Object value, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty() || !"valueMap".equals(payload.get("type"))) {
            return warning(value, WARNING_VALUE_MAP_INVALID_PAYLOAD);
        }
        var onMissing = payload.getOrDefault("onMissing", "keepOriginal");
        if (!"keepOriginal".equals(onMissing) && !"useDefault".equals(onMissing)) {
            return warning(value, WARNING_VALUE_MAP_INVALID_PAYLOAD);
        }
        var defaultValue = payload.get("defaultValue");
        if ("useDefault".equals(onMissing) && !(defaultValue instanceof String)) {
            return warning(value, WARNING_VALUE_MAP_INVALID_PAYLOAD);
        }
        var values = payload.get("values");
        if (!(values instanceof Map<?, ?> valueMap) || !validValues(valueMap)) {
            return warning(value, WARNING_VALUE_MAP_INVALID_VALUES);
        }
        if (value != null) {
            var key = String.valueOf(value);
            if (valueMap.containsKey(key)) {
                return new RuleApplication(valueMap.get(key), List.of());
            }
        }
        if ("useDefault".equals(onMissing)) {
            return new RuleApplication(defaultValue, List.of());
        }
        return warning(value, WARNING_VALUE_MAP_MISS);
    }

    private boolean validValues(Map<?, ?> values) {
        if (values.size() > VALUE_MAP_MAX_ENTRIES) {
            return false;
        }
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                return false;
            }
            if (key.length() > VALUE_MAP_MAX_KEY_LENGTH || value.length() > VALUE_MAP_MAX_VALUE_LENGTH) {
                return false;
            }
        }
        return true;
    }

    private RuleApplication applyLegacyUnary(Object value, String rule, String sourceField) {
        var openIndex = rule.indexOf('(');
        if (openIndex <= 0 || !rule.endsWith(")") || rule.indexOf('(', openIndex + 1) >= 0) {
            return warning(value, WARNING_INVALID);
        }
        var ruleName = rule.substring(0, openIndex);
        if (!isSimpleUnary(ruleName)) {
            return warning(value, WARNING_UNSUPPORTED);
        }
        var argument = rule.substring(openIndex + 1, rule.length() - 1);
        if (argument.isBlank() || argument.contains(",") || argument.contains("(") || argument.contains(")")) {
            return warning(value, WARNING_INVALID);
        }
        if (!argument.equals(sourceField)) {
            return warning(value, WARNING_MISMATCH);
        }
        return new RuleApplication(applyUnary(value, ruleName), List.of());
    }

    private Object applyUnary(Object value, String ruleName) {
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value);
        return switch (ruleName.toLowerCase(Locale.ROOT)) {
            case "trim" -> text.trim();
            case "lower" -> text.toLowerCase(Locale.ROOT);
            case "upper" -> text.toUpperCase(Locale.ROOT);
            default -> text;
        };
    }

    private Object defaultIfBlank(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    private boolean isSimpleUnary(String ruleName) {
        return "trim".equalsIgnoreCase(ruleName)
            || "lower".equalsIgnoreCase(ruleName)
            || "upper".equalsIgnoreCase(ruleName);
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private RuleApplication warning(Object value, String warning) {
        return new RuleApplication(value, List.of(warning));
    }

    record RuleApplication(Object value, List<String> warnings) {
        RuleApplication {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
