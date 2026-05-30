package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanFingerprintSupportTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlanFingerprintSupport support = new PlanFingerprintSupport(objectMapper);

    @Test
    void fingerprintUsesStableAlgorithmAndLowercaseSha256Hash() {
        var fingerprint = support.fingerprint("""
            {
              "version": "ingestion-plan-v1",
              "fieldMappings": {"id": "externalId"}
            }
            """);

        assertEquals("sha256-canonical-json-v1", fingerprint.algorithm());
        assertTrue(fingerprint.hash().matches("[0-9a-f]{64}"));
        assertEquals("sha256-canonical-json-v1", fingerprint.asMap().get("algorithm"));
        assertEquals(fingerprint.hash(), fingerprint.asMap().get("hash"));
    }

    @Test
    void fingerprintIgnoresObjectKeyOrderButPreservesArrayOrder() {
        var first = support.fingerprint("""
            {
              "fieldMappings": {"id": "externalId", "create_time": "occurredAt"},
              "dedupFields": ["id", "create_time"]
            }
            """);
        var reorderedObjects = support.fingerprint("""
            {
              "dedupFields": ["id", "create_time"],
              "fieldMappings": {"create_time": "occurredAt", "id": "externalId"}
            }
            """);
        var reorderedArray = support.fingerprint("""
            {
              "fieldMappings": {"create_time": "occurredAt", "id": "externalId"},
              "dedupFields": ["create_time", "id"]
            }
            """);

        assertEquals(first.hash(), reorderedObjects.hash());
        assertNotEquals(first.hash(), reorderedArray.hash());
    }

    @Test
    void fingerprintChangesWhenTransformRulePayloadChanges() {
        var base = support.fingerprint("""
            {
              "fieldMappings": {"user_account": "actor"},
              "fieldMappingDetails": [
                {
                  "sourceField": "user_account",
                  "standardField": "actor",
                  "transformRule": "valueMap",
                  "transformRulePayload": {
                    "type": "valueMap",
                    "values": {"USER_A": "mapped-user"},
                    "onMissing": "keepOriginal"
                  }
                }
              ]
            }
            """);
        var changedValue = support.fingerprint("""
            {
              "fieldMappings": {"user_account": "actor"},
              "fieldMappingDetails": [
                {
                  "sourceField": "user_account",
                  "standardField": "actor",
                  "transformRule": "valueMap",
                  "transformRulePayload": {
                    "type": "valueMap",
                    "values": {"USER_A": "admin-user"},
                    "onMissing": "keepOriginal"
                  }
                }
              ]
            }
            """);
        var changedDefault = support.fingerprint("""
            {
              "fieldMappings": {"user_account": "actor"},
              "fieldMappingDetails": [
                {
                  "sourceField": "user_account",
                  "standardField": "actor",
                  "transformRule": "valueMap",
                  "transformRulePayload": {
                    "type": "valueMap",
                    "values": {"USER_A": "mapped-user"},
                    "onMissing": "useDefault",
                    "defaultValue": "unknown-user"
                  }
                }
              ]
            }
            """);

        assertNotEquals(base.hash(), changedValue.hash());
        assertNotEquals(base.hash(), changedDefault.hash());
    }

    @Test
    void fingerprintMapDoesNotExposePlanJson() {
        var fingerprint = support.fingerprint(Map.of("fieldMappings", Map.of("id", "externalId"))).asMap();

        assertFalse(fingerprint.containsKey("planJson"));
        assertFalse(fingerprint.containsKey("plan_json"));
        assertFalse(String.valueOf(fingerprint).contains("externalId"));
    }
}
