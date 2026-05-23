package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
            "title",
            "external_id"
        )));
        assertTrue(match.missingSignals().containsAll(List.of(
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
}
