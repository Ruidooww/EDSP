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
