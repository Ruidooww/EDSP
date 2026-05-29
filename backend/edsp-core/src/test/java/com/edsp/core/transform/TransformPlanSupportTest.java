package com.edsp.core.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransformPlanSupportTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransformPlanSupport support = new TransformPlanSupport(
        objectMapper,
        new CoreRequestSupport(objectMapper)
    );

    @Test
    void mappingPlanCarriesFieldMappingDetailsWithoutChangingFieldMappings() {
        var mappingPlan = support.mappingPlan(Map.of(
            "fieldMappings", Map.of("id", "externalId"),
            "fieldMappingDetails", List.of(
                Map.of("sourceField", "id", "standardField", "actor", "transformRule", "lower"),
                Map.of("sourceField", "name", "standardField", "actor", "transformRule", " ")
            ),
            "dedupStrategy", Map.of("fields", List.of("id"))
        ));

        assertEquals(Map.of("id", "externalId"), mappingPlan.fieldMappings());
        assertEquals(List.of("id"), mappingPlan.dedupFields());
        assertEquals(2, mappingPlan.fieldMappingDetails().size());
        assertEquals("actor", mappingPlan.fieldMappingDetails().get(0).standardField());
        assertEquals("lower", mappingPlan.fieldMappingDetails().get(0).transformRule());
        assertEquals(" ", mappingPlan.fieldMappingDetails().get(1).transformRule());
    }

    @Test
    void mappingPlanPreservesNullTransformRule() {
        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("sourceField", "id");
        detail.put("standardField", "externalId");
        detail.put("transformRule", null);

        var mappingPlan = support.mappingPlan(Map.of(
            "fieldMappingDetails", List.of(detail)
        ));

        assertEquals(Map.of("id", "externalId"), mappingPlan.fieldMappings());
        assertEquals(1, mappingPlan.fieldMappingDetails().size());
        assertNull(mappingPlan.fieldMappingDetails().get(0).transformRule());
    }
}
