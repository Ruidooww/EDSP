package com.edsp.transform.standardevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
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
        assertApplication(
            "VALUE",
            List.of("transform_rule_unsupported"),
            applier.apply("VALUE", "valueMap", "source")
        );
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
}
