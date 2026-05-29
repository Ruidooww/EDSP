package com.edsp.transform.standardevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandardEventTransformServiceTest {
    private final StandardEventTransformService service = new StandardEventTransformService();

    @Test
    void mapsSourceRowToStandardEventDraftWithoutSerializingJson() {
        var result = service.transform(
            new SourceRow(Map.of(
                "id", "ALERT-1",
                "create_time", "2026-05-20 10:30:00",
                "event_name", "Sensitive file export",
                "user_account", "zhangsan",
                "host_name", "WIN-01",
                "risk_level", "high"
            )),
            defaultPlan(),
            defaultOptions()
        );

        assertTrue(result.errors().isEmpty());
        assertTrue(result.warnings().isEmpty());

        var draft = result.draft();
        assertNotNull(draft);
        assertEquals("ds:7:st:11", draft.sourceSystem());
        assertEquals("ALERT-1", draft.externalId());
        assertEquals("Sensitive file export", draft.eventType());
        assertEquals(ZoneOffset.ofHours(8), draft.occurredAt().getOffset());
        assertEquals("zhangsan", draft.actor());
        assertEquals("WIN-01", draft.assetRef());
        assertEquals("event", draft.subjectType());
        assertEquals("WIN-01", draft.subjectRef());
        assertNull(draft.action());
        assertEquals("detected", draft.result());
        assertEquals("high", draft.severity());
        assertEquals(80, draft.riskScore());
        assertEquals(
            "18120666e480d522a0e4ded9c372adbfa83e2a42dcc1eb722874acaa92346314",
            draft.dedupKey()
        );

        assertEquals("sec_alert_event", draft.normalized().get("sourceTable"));
        assertEquals("sync_once", draft.extra().get("syncMode"));
        assertEquals("sec_alert_event", draft.extra().get("sourceTable"));
        assertEquals(7L, draft.extra().get("dataSourceId"));
        var mapped = objectMap(draft.normalized().get("mapped"));
        assertEquals("ALERT-1", mapped.get("externalId"));
        assertEquals("Sensitive file export", mapped.get("title"));
    }

    @Test
    void reportsMissingOccurredAtButStillReturnsDraft() {
        var result = service.transform(
            new SourceRow(Map.of(
                "id", "ALERT-1",
                "event_name", "Sensitive file export",
                "user_account", "zhangsan",
                "host_name", "WIN-01",
                "risk_level", "high"
            )),
            defaultPlan(),
            defaultOptions()
        );

        assertEquals(List.of("missing_occurred_at"), result.errors());
        assertNotNull(result.draft());
        assertNull(result.draft().occurredAt());
    }

    @Test
    void reportsInvalidOccurredAtFormatButStillReturnsDraft() {
        var result = service.transform(
            rowWith("create_time", "not-a-time"),
            defaultPlan(),
            defaultOptions()
        );

        assertEquals(List.of("invalid_time_format"), result.errors());
        assertNotNull(result.draft());
        assertNull(result.draft().occurredAt());
    }

    @Test
    void parsesSupportedTimeFormatsWithDefaultAsiaEightOffset() {
        assertEquals(ZoneOffset.UTC, transformTime("2026-05-20T10:30:00Z").getOffset());
        assertEquals(ZoneOffset.ofHours(8), transformTime("2026-05-20T10:30:00").getOffset());
        assertEquals(ZoneOffset.ofHours(8), transformTime("2026/05/20 10:30:00").getOffset());
        assertEquals(ZoneOffset.ofHours(8), transformTime("2026/05/20").getOffset());
        assertEquals(ZoneOffset.ofHours(8), transformTime("1716172200").getOffset());
        assertEquals(ZoneOffset.ofHours(8), transformTime("1716172200000").getOffset());
    }

    @Test
    void normalizesSeverityAndRiskScoreWithCurrentSyncOnceSemantics() {
        assertSeverity("critical", "critical", 95, List.of());
        assertSeverity("high", "high", 80, List.of());
        assertSeverity("medium", "medium", 55, List.of());
        assertSeverity("low", "low", 25, List.of());
        assertSeverity("info", "info", 10, List.of());
        assertSeverity("warning", "medium", 55, List.of());
        assertSeverity("1", "critical", 95, List.of());
        assertSeverity("2", "high", 80, List.of());
        assertSeverity("3", "medium", 55, List.of());
        assertSeverity("4", "low", 25, List.of());
        assertSeverity("unknown", "info", 10, List.of("severity_unrecognized"));

        var nullSeverity = service.transform(
            new SourceRow(Map.of(
                "id", "ALERT-1",
                "create_time", "2026-05-20 10:30:00",
                "event_name", "Sensitive file export"
            )),
            defaultPlan(),
            defaultOptions()
        );
        assertEquals("info", nullSeverity.draft().severity());
        assertFalse(nullSeverity.errors().contains("severity_unrecognized"));
    }

    @Test
    void usesExternalIdDedupWhenNoConfiguredDedupFieldsExist() {
        var result = service.transform(
            rowWith("create_time", "2026-05-20 10:30:00"),
            new MappingPlan(defaultMappings(), List.of()),
            defaultOptions()
        );

        assertEquals(
            "07113ea3655d3c9b46ecc7ad2d69a17525bfa1ba7668bc28ec98884203d799bf",
            result.draft().dedupKey()
        );
    }

    @Test
    void reportsMissingDedupKeyWhenConfiguredDedupFieldIsBlank() {
        var result = service.transform(
            new SourceRow(Map.of(
                "id", " ",
                "create_time", "2026-05-20 10:30:00",
                "event_name", "Sensitive file export",
                "risk_level", "high"
            )),
            defaultPlan(),
            defaultOptions()
        );

        assertEquals(List.of("dedup_key_missing"), result.errors());
        assertNull(result.draft().dedupKey());
    }

    @Test
    void parsesMappingPlanFromCurrentPlanJsonShape() {
        var plan = MappingPlan.fromPlan(Map.of(
            "fieldMappings", Map.of("id", "externalId"),
            "dedupStrategy", Map.of("fields", List.of("id"))
        ));

        assertEquals(Map.of("id", "externalId"), plan.fieldMappings());
        assertEquals(List.of("id"), plan.dedupFields());

        var detailPlan = MappingPlan.fromPlan(Map.of(
            "fieldMappingDetails", List.of(Map.of("sourceField", "create_time", "standardField", "occurredAt")),
            "dedupStrategy", Map.of("fields", "id")
        ));
        assertEquals(Map.of(), detailPlan.fieldMappings());
        assertEquals(1, detailPlan.fieldMappingDetails().size());
        assertEquals(List.of("id"), detailPlan.dedupFields());
    }

    @Test
    void fieldMappingDetailsAloneDoNotBecomeAuthoritativeMappings() {
        var plan = MappingPlan.fromPlan(Map.of(
            "fieldMappingDetails", List.of(Map.of(
                "sourceField", "user_account",
                "standardField", "actor",
                "transformRule", "lower"
            )),
            "dedupStrategy", Map.of("fields", List.of("id"))
        ));

        var result = service.transform(
            new SourceRow(Map.of(
                "id", "ALERT-1",
                "create_time", "2026-05-20 10:30:00",
                "user_account", "USER_A"
            )),
            plan,
            defaultOptions()
        );

        assertEquals(Map.of(), plan.fieldMappings());
        assertEquals(1, plan.fieldMappingDetails().size());
        assertEquals(List.of("missing_occurred_at"), result.errors());
        assertEquals(List.of(), result.warnings());
        assertNull(result.draft().actor());
        assertEquals(Map.of(), result.draft().normalized().get("mapped"));
    }

    @Test
    void mappingPlanCarriesDetailsButFieldMappingsRemainAuthoritative() {
        var plan = MappingPlan.fromPlan(Map.of(
            "fieldMappings", Map.of("id", "externalId"),
            "fieldMappingDetails", List.of(Map.of(
                "sourceField", "id",
                "standardField", "actor",
                "transformRule", "lower"
            )),
            "dedupStrategy", Map.of("fields", List.of("id"))
        ));

        assertEquals(Map.of("id", "externalId"), plan.fieldMappings());
        assertEquals(List.of("id"), plan.dedupFields());
        assertEquals(1, plan.fieldMappingDetails().size());
        assertEquals("id", plan.fieldMappingDetails().get(0).sourceField());
        assertEquals("actor", plan.fieldMappingDetails().get(0).standardField());
        assertEquals("lower", plan.fieldMappingDetails().get(0).transformRule());
    }

    @Test
    void transformRulesInAuthoritativeMappingDetailsChangeMappedOutputOnly() {
        var row = new SourceRow(Map.of(
            "id", "ALERT-1",
            "create_time", "2026-05-20 10:30:00",
            "event_name", "Sensitive file export",
            "user_account", "USER_A",
            "risk_level", "HIGH"
        ));
        var baseline = service.transform(
            row,
            new MappingPlan(defaultMappings(), List.of("id")),
            defaultOptions()
        );
        var withRulePayload = service.transform(
            row,
            new MappingPlan(
                defaultMappings(),
                List.of("id"),
                List.of(
                    new MappingPlan.FieldMappingDetail("user_account", "actor", "lower"),
                    new MappingPlan.FieldMappingDetail("risk_level", "actor", "lower")
                )
            ),
            defaultOptions()
        );

        assertEquals(baseline.errors(), withRulePayload.errors());
        assertEquals(baseline.warnings(), withRulePayload.warnings());
        assertEquals(baseline.draft().dedupKey(), withRulePayload.draft().dedupKey());
        assertEquals("USER_A", baseline.draft().actor());
        assertEquals("user_a", withRulePayload.draft().actor());
        assertEquals("user_a", objectMap(withRulePayload.draft().normalized().get("mapped")).get("actor"));
    }

    private void assertSeverity(String raw, String expectedSeverity, Integer expectedRisk, List<String> expectedErrors) {
        var result = service.transform(rowWith("risk_level", raw), defaultPlan(), defaultOptions());

        assertEquals(expectedSeverity, result.draft().severity());
        assertEquals(expectedRisk, result.draft().riskScore());
        assertEquals(expectedErrors, result.errors());
    }

    private java.time.OffsetDateTime transformTime(String value) {
        var result = service.transform(rowWith("create_time", value), defaultPlan(), defaultOptions());
        assertTrue(result.errors().isEmpty());
        return result.draft().occurredAt();
    }

    private SourceRow rowWith(String key, Object value) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("id", "ALERT-1");
        row.put("create_time", "2026-05-20 10:30:00");
        row.put("event_name", "Sensitive file export");
        row.put("user_account", "zhangsan");
        row.put("host_name", "WIN-01");
        row.put("risk_level", "high");
        row.put(key, value);
        return new SourceRow(row);
    }

    private MappingPlan defaultPlan() {
        return new MappingPlan(defaultMappings(), List.of("id"));
    }

    private Map<String, String> defaultMappings() {
        return Map.of(
            "id", "externalId",
            "create_time", "occurredAt",
            "event_name", "title",
            "user_account", "actor",
            "host_name", "assetRef",
            "risk_level", "severity"
        );
    }

    private TransformOptions defaultOptions() {
        return new TransformOptions(7L, 11L, "sec_alert_event", "sync_once");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
