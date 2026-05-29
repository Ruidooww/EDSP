package com.edsp.transform.standardevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.edsp.transform.standardevent.dedup.DedupKeyBuilder;
import com.edsp.transform.standardevent.normalize.RiskScoreCalculator;
import com.edsp.transform.standardevent.normalize.SeverityNormalizer;
import com.edsp.transform.standardevent.normalize.TimeValueParser;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandardEventTransformRuleProcessorTest {
    private final StandardEventTransformRuleProcessor processor = new StandardEventTransformRuleProcessor(
        new TimeValueParser(),
        new SeverityNormalizer(),
        new RiskScoreCalculator(),
        new DedupKeyBuilder()
    );

    @Test
    void appliesCurrentRulesAndKeepsDraftShape() {
        var result = processor.process(
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

        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());

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
    void appliesRulesOnlyForExactAuthoritativeFieldMappingDetails() {
        var row = new SourceRow(Map.of(
            "id", "ALERT-1",
            "create_time", " 2026-05-20 10:30:00 ",
            "event_name", "Sensitive file export",
            "user_account", "USER_A",
            "host_name", "win-01",
            "risk_level", "HIGH",
            "action_raw", ""
        ));
        var mappings = new LinkedHashMap<String, String>();
        mappings.put("id", "externalId");
        mappings.put("create_time", "occurredAt");
        mappings.put("event_name", "title");
        mappings.put("user_account", "actor");
        mappings.put("host_name", "assetRef");
        mappings.put("risk_level", "severity");
        mappings.put("action_raw", "action");
        var plan = new MappingPlan(
            mappings,
            List.of("id"),
            List.of(
                new MappingPlan.FieldMappingDetail("create_time", "occurredAt", "trim"),
                new MappingPlan.FieldMappingDetail("user_account", "actor", "lower"),
                new MappingPlan.FieldMappingDetail("host_name", "assetRef", "upper"),
                new MappingPlan.FieldMappingDetail("action_raw", "action", "defaultIfBlank:C:\\Users"),
                new MappingPlan.FieldMappingDetail("different_source", "actor", "upper"),
                new MappingPlan.FieldMappingDetail("risk_level", "actor", "lower")
            )
        );

        var result = processor.process(row, plan, defaultOptions());

        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());
        assertEquals("user_a", result.draft().actor());
        assertEquals("WIN-01", result.draft().assetRef());
        assertEquals("C:\\Users", result.draft().action());
        assertEquals("high", result.draft().severity());
        assertEquals(ZoneOffset.ofHours(8), result.draft().occurredAt().getOffset());
        var mapped = objectMap(result.draft().normalized().get("mapped"));
        assertEquals("user_a", mapped.get("actor"));
        assertEquals("WIN-01", mapped.get("assetRef"));
        assertEquals("C:\\Users", mapped.get("action"));
    }

    @Test
    void reportsRuleWarningsWithoutChangingRowSuccessOrRawSourceRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", "ALERT-1");
        row.put("create_time", "2026-05-20 10:30:00");
        row.put("event_name", "Sensitive file export");
        row.put("user_account", "USER_A");
        row.put("host_name", "WIN-01");
        row.put("risk_level", "high");
        var sourceRow = new SourceRow(row);
        var plan = new MappingPlan(
            defaultMappings(),
            List.of("id"),
            List.of(
                new MappingPlan.FieldMappingDetail("user_account", "actor", "lower(FilePath)"),
                new MappingPlan.FieldMappingDetail("host_name", "assetRef", "valueMap"),
                new MappingPlan.FieldMappingDetail("risk_level", "severity", "defaultIfBlank")
            )
        );

        var result = processor.process(sourceRow, plan, defaultOptions());

        assertEquals(List.of(), result.errors());
        assertEquals(List.of(
            "transform_rule_mismatch",
            "transform_rule_unsupported",
            "transform_rule_invalid"
        ), result.warnings());
        assertEquals("USER_A", result.draft().actor());
        assertEquals("WIN-01", result.draft().assetRef());
        assertEquals("high", result.draft().severity());
        assertEquals("USER_A", sourceRow.values().get("user_account"));
        assertEquals("WIN-01", sourceRow.values().get("host_name"));
    }

    @Test
    void ignoresExtraAndNonAuthoritativeDetailsWithoutWarnings() {
        var plan = new MappingPlan(
            Map.of("user_account", "actor"),
            List.of(),
            List.of(
                new MappingPlan.FieldMappingDetail("other_source", "actor", "lower"),
                new MappingPlan.FieldMappingDetail("user_account", "otherStandard", "lower"),
                new MappingPlan.FieldMappingDetail("unused", "unusedStandard", "lower")
            )
        );

        var result = processor.process(
            new SourceRow(Map.of("user_account", "USER_A")),
            plan,
            defaultOptions()
        );

        assertEquals(List.of("missing_occurred_at"), result.errors());
        assertEquals(List.of(), result.warnings());
        assertEquals("USER_A", result.draft().actor());
        assertEquals("USER_A", objectMap(result.draft().normalized().get("mapped")).get("actor"));
    }

    @Test
    void keepsDedupKeyBasedOnRawSourceRowWhenRuleChangesMappedValue() {
        var row = new SourceRow(Map.of(
            "id", "ALERT-1",
            "create_time", "2026-05-20 10:30:00",
            "event_name", "Sensitive file export",
            "user_account", "USER_A",
            "host_name", "WIN-01",
            "risk_level", "high"
        ));
        var baseline = processor.process(
            row,
            new MappingPlan(defaultMappings(), List.of("user_account")),
            defaultOptions()
        );
        var withRule = processor.process(
            row,
            new MappingPlan(
                defaultMappings(),
                List.of("user_account"),
                List.of(new MappingPlan.FieldMappingDetail("user_account", "actor", "lower"))
            ),
            defaultOptions()
        );

        assertEquals("USER_A", baseline.draft().actor());
        assertEquals("user_a", withRule.draft().actor());
        assertEquals(baseline.draft().dedupKey(), withRule.draft().dedupKey());
    }

    @Test
    void preservesErrorOrderAndReturnsDraftWhenRulesFail() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", " ");
        row.put("create_time", "not-a-time");
        row.put("event_name", "Sensitive file export");
        row.put("risk_level", "unknown");

        var result = processor.process(new SourceRow(row), defaultPlan(), defaultOptions());

        assertEquals(List.of("invalid_time_format", "severity_unrecognized", "dedup_key_missing"), result.errors());
        assertEquals(List.of(), result.warnings());
        assertNotNull(result.draft());
        assertNull(result.draft().occurredAt());
        assertEquals("info", result.draft().severity());
        assertEquals(10, result.draft().riskScore());
        assertNull(result.draft().dedupKey());
    }

    @Test
    void appliesCurrentDefaultsForNullInputs() {
        var result = processor.process(null, null, null);

        assertEquals(List.of("missing_occurred_at"), result.errors());
        assertEquals(List.of(), result.warnings());

        var draft = result.draft();
        assertNotNull(draft);
        assertEquals("external", draft.sourceSystem());
        assertNull(draft.externalId());
        assertEquals("ingestion_plan_event", draft.eventType());
        assertNull(draft.occurredAt());
        assertEquals("event", draft.subjectType());
        assertNull(draft.subjectRef());
        assertEquals("detected", draft.result());
        assertEquals("info", draft.severity());
        assertEquals(10, draft.riskScore());
        assertNotNull(draft.dedupKey());
        assertNull(draft.normalized().get("sourceTable"));
        assertEquals(Map.of(), draft.normalized().get("mapped"));
        assertNull(draft.extra().get("syncMode"));
        assertNull(draft.extra().get("sourceTable"));
        assertNull(draft.extra().get("dataSourceId"));
        assertFalse(draft.extra().containsKey("password"));
    }

    @Test
    void serviceFacadeDelegatesToProcessorSemantics() {
        var service = new StandardEventTransformService();
        var row = new SourceRow(Map.of(
            "id", "ALERT-1",
            "create_time", "2026-05-20 10:30:00",
            "event_name", "Sensitive file export",
            "user_account", "zhangsan",
            "host_name", "WIN-01",
            "risk_level", "high"
        ));

        var serviceResult = service.transform(row, defaultPlan(), defaultOptions());
        var processorResult = processor.process(row, defaultPlan(), defaultOptions());

        assertEquals(processorResult.errors(), serviceResult.errors());
        assertEquals(processorResult.warnings(), serviceResult.warnings());
        assertEquals(processorResult.draft(), serviceResult.draft());
    }

    private MappingPlan defaultPlan() {
        return new MappingPlan(defaultMappings(), List.of("id"));
    }

    private Map<String, String> defaultMappings() {
        var mappings = new LinkedHashMap<String, String>();
        mappings.put("id", "externalId");
        mappings.put("create_time", "occurredAt");
        mappings.put("event_name", "title");
        mappings.put("user_account", "actor");
        mappings.put("host_name", "assetRef");
        mappings.put("risk_level", "severity");
        return Collections.unmodifiableMap(mappings);
    }

    private TransformOptions defaultOptions() {
        return new TransformOptions(7L, 11L, "sec_alert_event", "sync_once");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
