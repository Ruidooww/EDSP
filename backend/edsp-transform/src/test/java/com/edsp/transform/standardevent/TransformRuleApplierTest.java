package com.edsp.transform.standardevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransformRuleApplierTest {
    private final TransformRuleApplier applier = new TransformRuleApplier();

    @Test
    void treatsNullBlankAndWhitespaceRulesAsNoRule() {
        assertApplication("VALUE", List.of(), applier.apply("VALUE", null, "source"));
        assertApplication("VALUE", List.of(), applier.apply("VALUE", "", "source"));
        assertApplication("VALUE", List.of(), applier.apply("VALUE", "   ", "source"));
    }

    @Test
    void appliesTrimLowerAndUpperCaseInsensitive() {
        assertApplication("value", List.of(), applier.apply(" value ", "trim", "source"));
        assertApplication("value", List.of(), applier.apply("VALUE", "Lower", "source"));
        assertApplication("VALUE", List.of(), applier.apply("value", "UPPER", "source"));
    }

    @Test
    void keepsNullValuesForUnaryRulesAndConvertsNumbersToStrings() {
        assertApplication(null, List.of(), applier.apply(null, "trim", "source"));
        assertApplication(null, List.of(), applier.apply(null, "upper", "source"));
        assertApplication("42", List.of(), applier.apply(42, "lower", "source"));
    }

    @Test
    void appliesDefaultIfBlankUsingEverythingAfterFirstColon() {
        assertApplication("N/A", List.of(), applier.apply(null, "defaultIfBlank:N/A", "source"));
        assertApplication("N/A", List.of(), applier.apply("", "defaultIfBlank:N/A", "source"));
        assertApplication("N/A", List.of(), applier.apply("   ", "defaultIfBlank:N/A", "source"));
        assertApplication("N/A ", List.of(), applier.apply("", " defaultIfBlank:N/A ", "source"));
        assertApplication("N/A  ", List.of(), applier.apply("", "defaultIfBlank:N/A  ", "source"));
        assertApplication("C:\\Users", List.of(), applier.apply("", "defaultIfBlank:C:\\Users", "source"));
        assertApplication("", List.of(), applier.apply("", "defaultIfBlank:", "source"));
        assertApplication("present", List.of(), applier.apply("present", "defaultIfBlank:N/A", "source"));
    }

    @Test
    void appliesLegacyUnaryFormOnlyWhenSourceFieldMatches() {
        assertApplication("Alice", List.of(), applier.apply(" Alice ", "trim(UserName)", "UserName"));
        assertApplication("alice", List.of(), applier.apply("ALICE", "lower(UserName)", "UserName"));
        assertApplication("ALICE", List.of(), applier.apply("alice", "upper(UserName)", "UserName"));
        assertApplication(
            "ALICE",
            List.of("transform_rule_mismatch"),
            applier.apply("ALICE", "lower(FilePath)", "UserName")
        );
    }

    @Test
    void reportsUnsupportedAndInvalidRulesAsWarningsWithoutChangingValue() {
        assertApplication("VALUE", List.of("transform_rule_unsupported"), applier.apply("VALUE", "notARule", "source"));
        assertApplication(
            "VALUE",
            List.of("transform_rule_invalid"),
            applier.apply("VALUE", "lower(", "source")
        );
        assertApplication(
            "VALUE",
            List.of("transform_rule_invalid"),
            applier.apply("VALUE", "trim()", "source")
        );
        assertApplication(
            "VALUE",
            List.of("transform_rule_invalid"),
            applier.apply("VALUE", "defaultIfBlank", "source")
        );
    }

    @Test
    void appliesValueMapHitsAndMissingKeySemantics() {
        assertApplication(
            "high",
            List.of(),
            applier.apply("critical", " valueMap ", "source", valueMapPayload(Map.of("critical", "high")))
        );
        assertApplication(
            "unknown",
            List.of("transform_rule_value_map_miss"),
            applier.apply("unknown", "valueMap", "source", valueMapPayload(Map.of("critical", "high")))
        );
        assertApplication(
            "unknown",
            List.of("transform_rule_value_map_miss"),
            applier.apply("unknown", "valueMap", "source", Map.of("type", "valueMap", "values", Map.of()))
        );
        assertApplication(
            "info",
            List.of(),
            applier.apply(
                "unknown",
                "valueMap",
                "source",
                payload("type", "valueMap", "values", Map.of("critical", "high"), "onMissing", "useDefault", "defaultValue", "info")
            )
        );
    }

    @Test
    void appliesValueMapNullSourceValueSemantics() {
        assertApplication(
            null,
            List.of("transform_rule_value_map_miss"),
            applier.apply(null, "valueMap", "source", valueMapPayload(Map.of("null", "not-used")))
        );
        assertApplication(
            "info",
            List.of(),
            applier.apply(
                null,
                "valueMap",
                "source",
                payload("type", "valueMap", "values", Map.of("critical", "high"), "onMissing", "useDefault", "defaultValue", "info")
            )
        );
    }

    @Test
    void reportsValueMapInvalidPayloadWithoutMissWarning() {
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_payload"),
            applier.apply("critical", "valueMap", "source")
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_payload"),
            applier.apply("critical", "valueMap", "source", Map.of("values", Map.of("critical", "high")))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_payload"),
            applier.apply("critical", "valueMap", "source", payload("type", "other", "values", Map.of("critical", "high")))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_payload"),
            applier.apply("critical", "valueMap", "source", payload("type", "valueMap", "values", Map.of("critical", "high"), "onMissing", "blank"))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_payload"),
            applier.apply("critical", "valueMap", "source", payload("type", "valueMap", "values", Map.of(), "onMissing", "useDefault"))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_payload"),
            applier.apply("critical", "valueMap", "source", payload("type", "valueMap", "values", Map.of(), "onMissing", "useDefault", "defaultValue", 1))
        );
    }

    @Test
    void reportsValueMapInvalidValuesWithoutMissWarning() {
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_values"),
            applier.apply("critical", "valueMap", "source", Map.of("type", "valueMap"))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_values"),
            applier.apply("critical", "valueMap", "source", payload("type", "valueMap", "values", List.of("critical")))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_values"),
            applier.apply("critical", "valueMap", "source", payloadWithRawValues(Map.of(7, "high")))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_values"),
            applier.apply("critical", "valueMap", "source", payload("type", "valueMap", "values", Map.of("critical", 7)))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_values"),
            applier.apply("critical", "valueMap", "source", valueMapPayload(sizedValues(201, 10, 10)))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_values"),
            applier.apply("critical", "valueMap", "source", valueMapPayload(Map.of("k".repeat(201), "high")))
        );
        assertApplication(
            "critical",
            List.of("transform_rule_value_map_invalid_values"),
            applier.apply("critical", "valueMap", "source", valueMapPayload(Map.of("critical", "v".repeat(501))))
        );
    }

    @Test
    void acceptsValueMapBoundarySizes() {
        assertApplication(
            "mapped-199",
            List.of(),
            applier.apply("key-199", "valueMap", "source", valueMapPayload(sizedValues(200, 7, 10)))
        );
        assertApplication(
            "high",
            List.of(),
            applier.apply("k".repeat(200), "valueMap", "source", valueMapPayload(Map.of("k".repeat(200), "high")))
        );
        assertApplication(
            "v".repeat(500),
            List.of(),
            applier.apply("critical", "valueMap", "source", valueMapPayload(Map.of("critical", "v".repeat(500))))
        );
    }

    @Test
    void ignoresPayloadForNonValueMapRules() {
        var payload = valueMapPayload(Map.of("VALUE", "mapped"));

        assertApplication("VALUE", List.of(), applier.apply("value", "upper", "source", payload));
        assertApplication(
            "VALUE",
            List.of("transform_rule_unsupported"),
            applier.apply("VALUE", "notARule", "source", payload)
        );
    }

    private void assertApplication(
        Object expectedValue,
        List<String> expectedWarnings,
        TransformRuleApplier.RuleApplication application
    ) {
        if (expectedValue == null) {
            assertNull(application.value());
        } else {
            assertEquals(expectedValue, application.value());
        }
        assertEquals(expectedWarnings, application.warnings());
    }

    private Map<String, Object> valueMapPayload(Map<?, ?> values) {
        return payload("type", "valueMap", "values", values);
    }

    private Map<String, Object> payload(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (var i = 0; i < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> payloadWithRawValues(Map<?, ?> rawValues) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "valueMap");
        result.put("values", (Map) rawValues);
        return result;
    }

    private Map<String, String> sizedValues(int entries, int keyLength, int valueLength) {
        var result = new LinkedHashMap<String, String>();
        for (var i = 0; i < entries; i++) {
            var key = ("key-" + i).substring(0, Math.min(("key-" + i).length(), keyLength));
            if (key.length() < keyLength) {
                key = key + "k".repeat(keyLength - key.length());
            }
            result.put(key, "mapped-" + i);
        }
        return result;
    }
}
