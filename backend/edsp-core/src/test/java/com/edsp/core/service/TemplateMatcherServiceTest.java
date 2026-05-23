package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateMatcherServiceTest {
    private final TemplateMatcherService service = new TemplateMatcherService();

    @Test
    void matchExplainsAlertSignalsBySemanticRole() {
        var match = service.match(
            "SEC_ALERT_EVENT",
            "alert_table",
            List.of("ID", "CREATE_TIME", "SEVERITY", "USER_ACCOUNT", "HOST_NAME", "EVENT_NAME")
        );

        assertTrue(match.matchedSignals().containsAll(List.of(
            "occurred_at",
            "severity",
            "actor",
            "asset_ref",
            "title"
        )));
        assertFalse(match.matchedSignals().contains("external_id"));
        assertTrue(match.missingSignals().containsAll(List.of(
            "external_id",
            "policy_name",
            "result",
            "subject_ref"
        )));
    }

    @Test
    void matchDoesNotInferTitleFromGenericEventHostOrPolicyNames() {
        var match = service.match(
            "SEC_ALERT_EVENT",
            "alert_table",
            List.of("EVENT_TIME", "EVENT_ID", "HOST_NAME", "POLICY_NAME")
        );

        assertFalse(match.matchedSignals().contains("title"));
        assertTrue(match.missingSignals().contains("title"));
    }

    @Test
    void matchExplainsSignalsWithAliasEvidence() {
        var match = service.match(
            "SEC_ALERT_EVENT",
            "alert_table",
            List.of("EVENT_TIME", "告警级别", "账号", "IP", "POLICY_NAME", "处理结果", "目标对象")
        );

        assertSignalEvidence(match, "occurred_at", "field_name", List.of("EVENT_TIME"));
        assertSignalEvidence(match, "severity", "field_name", List.of("告警级别"));
        assertSignalEvidence(match, "actor", "field_name", List.of("账号"));
        assertSignalEvidence(match, "asset_ref", "field_name", List.of("IP"));
        assertSignalEvidence(match, "policy_name", "field_name", List.of("POLICY_NAME"));
        assertSignalEvidence(match, "result", "field_name", List.of("处理结果"));
        assertSignalEvidence(match, "subject_ref", "field_name", List.of("目标对象"));
    }

    @Test
    void matchDoesNotTreatScopedOrGenericIdsAsExternalIdEvidence() {
        var match = service.match(
            "SEC_ALERT_EVENT",
            "alert_table",
            List.of("ID", "USER_ID", "ASSET_ID", "EVENT_TIME", "EVENT_ID")
        );

        assertTrue(match.matchedSignals().contains("external_id"));
        assertSignalEvidence(match, "external_id", "field_name", List.of("EVENT_ID"));
        var externalIdEvidence = signalEvidence(match, "external_id");
        assertFalse(sourceFields(externalIdEvidence).contains("ID"));
        assertFalse(sourceFields(externalIdEvidence).contains("USER_ID"));
        assertFalse(sourceFields(externalIdEvidence).contains("ASSET_ID"));
    }

    @Test
    void matchAvoidsBroadSubstringMatchesForLogTemplate() {
        var loginMatch = service.match("SYS_USER_PROFILE", "unknown", List.of("LOGIN_NAME"));
        var catalogMatch = service.match("CATALOG_RECORDS", "unknown", List.of("CATALOG_ID"));

        assertFalse(loginMatch.mainPlanCandidate());
        assertEquals("user_table", loginMatch.templateKey());
        assertEquals("unknown", catalogMatch.templateKey());
    }

    private void assertSignalEvidence(
        TemplateMatcherService.TemplateMatch match,
        String signal,
        String source,
        List<String> sourceFields
    ) {
        var evidence = signalEvidence(match, signal);
        assertEquals(signal, value(evidence, "signal"));
        assertEquals(source, value(evidence, "source"));
        assertEquals(sourceFields, sourceFields(evidence));
        assertThrows(NoSuchMethodException.class, () -> evidence.getClass().getMethod("confidence"));
        assertThrows(NoSuchMethodException.class, () -> evidence.getClass().getMethod("reason"));
    }

    private Object signalEvidence(TemplateMatcherService.TemplateMatch match, String signal) {
        return signalEvidence(match).stream()
            .filter(evidence -> signal.equals(value(evidence, "signal")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Signal evidence not found: " + signal));
    }

    @SuppressWarnings("unchecked")
    private List<Object> signalEvidence(TemplateMatcherService.TemplateMatch match) {
        try {
            return (List<Object>) match.getClass().getMethod("signalEvidence").invoke(match);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("TemplateMatch should expose signalEvidence", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> sourceFields(Object evidence) {
        return (List<String>) value(evidence, "sourceFields");
    }

    private Object value(Object evidence, String accessor) {
        try {
            return evidence.getClass().getMethod(accessor).invoke(evidence);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("SignalEvidence should expose " + accessor, ex);
        }
    }
}
