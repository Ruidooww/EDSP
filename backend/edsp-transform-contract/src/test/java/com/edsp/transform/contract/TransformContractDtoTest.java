package com.edsp.transform.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransformContractDtoTest {
    @Test
    void contractDtosRepresentSingleAndBatchTransformPayloads() {
        var mappingPlan = new TransformMappingPlanDto(
            Map.of("id", "externalId", "create_time", "occurredAt"),
            List.of("id")
        );
        var options = new TransformOptionsDto(7L, 11L, "sec_alert_event", "sync_once");
        var request = new TransformRequest(Map.of("id", "ALERT-1"), mappingPlan, options);
        var draft = new TransformDraftDto(
            "ds:7:st:11",
            "ALERT-1",
            "Sensitive file export",
            "2026-05-20T10:30:00+08:00",
            "zhangsan",
            "WIN-01",
            "event",
            "WIN-01",
            null,
            "detected",
            "high",
            80,
            "dedup",
            Map.of("sourceTable", "sec_alert_event"),
            Map.of("syncMode", "sync_once")
        );
        var response = new TransformResponse(draft, List.of(), List.of());
        var batch = new BatchTransformRequest(List.of(request.row()), mappingPlan, options);
        var batchResponse = new BatchTransformResponse(List.of(new TransformResultItem(0, draft, List.of(), List.of())), List.of(), List.of());

        assertEquals("externalId", request.mappingPlan().fieldMappings().get("id"));
        assertEquals("sync_once", request.options().syncMode());
        assertEquals("2026-05-20T10:30:00+08:00", response.draft().occurredAt());
        assertNull(response.draft().action());
        assertEquals(1, batch.rows().size());
        assertEquals(0, batchResponse.results().get(0).index());
        assertTrue(batchResponse.errors().isEmpty());
    }

    @Test
    void mappingPlanKeepsOldConstructorAndNormalizesMissingDetails() {
        var mappingPlan = new TransformMappingPlanDto(
            Map.of("id", "externalId"),
            List.of("id")
        );

        assertEquals("externalId", mappingPlan.fieldMappings().get("id"));
        assertEquals(List.of("id"), mappingPlan.dedupFields());
        assertTrue(mappingPlan.fieldMappingDetails().isEmpty());
    }

    @Test
    void mappingPlanCarriesFieldMappingDetailsWithoutParsingRules() {
        var details = List.of(
            new TransformFieldMappingDto("id", "externalId", null),
            new TransformFieldMappingDto("name", "actor", "  ")
        );

        var mappingPlan = new TransformMappingPlanDto(
            Map.of("id", "externalId", "name", "actor"),
            List.of("id"),
            details
        );

        assertEquals(2, mappingPlan.fieldMappingDetails().size());
        assertNull(mappingPlan.fieldMappingDetails().get(0).transformRule());
        assertEquals("  ", mappingPlan.fieldMappingDetails().get(1).transformRule());
    }

    @Test
    void mappingPlanNormalizesNullFieldMappingDetailsToEmptyList() {
        var mappingPlan = new TransformMappingPlanDto(
            Map.of("id", "externalId"),
            List.of("id"),
            null
        );

        assertEquals(Map.of("id", "externalId"), mappingPlan.fieldMappings());
        assertEquals(List.of("id"), mappingPlan.dedupFields());
        assertEquals(List.of(), mappingPlan.fieldMappingDetails());
    }

    @Test
    void mappingPlanDefensivelyCopiesAllCollections() {
        var mappings = new LinkedHashMap<String, String>();
        mappings.put("id", "externalId");
        var dedupFields = new ArrayList<String>();
        dedupFields.add("id");
        var details = new ArrayList<TransformFieldMappingDto>();
        details.add(new TransformFieldMappingDto("id", "externalId", "trim"));

        var mappingPlan = new TransformMappingPlanDto(mappings, dedupFields, details);

        mappings.put("changed", "actor");
        dedupFields.add("changed");
        details.add(new TransformFieldMappingDto("changed", "actor", "lower"));

        assertEquals(Map.of("id", "externalId"), mappingPlan.fieldMappings());
        assertEquals(List.of("id"), mappingPlan.dedupFields());
        assertEquals(1, mappingPlan.fieldMappingDetails().size());
        assertThrows(UnsupportedOperationException.class, () -> mappingPlan.fieldMappings().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> mappingPlan.dedupFields().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> mappingPlan.fieldMappingDetails().add(
            new TransformFieldMappingDto("x", "y", "upper")
        ));
    }
}
