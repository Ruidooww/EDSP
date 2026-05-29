package com.edsp.transform.standardevent;

import java.util.List;
import java.util.Locale;

final class TransformRuleApplier {
    static final String WARNING_UNSUPPORTED = "transform_rule_unsupported";
    static final String WARNING_INVALID = "transform_rule_invalid";
    static final String WARNING_MISMATCH = "transform_rule_mismatch";

    private static final String DEFAULT_IF_BLANK_PREFIX = "defaultIfBlank:";

    RuleApplication apply(Object value, String transformRule, String sourceField) {
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
        if (rule.contains("(") || rule.contains(")")) {
            return applyLegacyUnary(value, rule, sourceField);
        }
        return warning(value, WARNING_UNSUPPORTED);
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
