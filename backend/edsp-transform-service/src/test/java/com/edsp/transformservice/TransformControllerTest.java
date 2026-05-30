package com.edsp.transformservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edsp.transform.contract.TransformFieldMappingDto;
import com.edsp.transform.contract.TransformMappingPlanDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransformControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void mapperCarriesFieldMappingDetailsAndLeavesExecutionToTransformModule() {
        var mappingPlan = TransformContractMapper.mappingPlan(new TransformMappingPlanDto(
            Map.of("risk_level", "severity"),
            List.of(),
            List.of(new TransformFieldMappingDto(
                "risk_level",
                "severity",
                "lower",
                Map.of("type", "valueMap", "values", Map.of("critical", "high"))
            ))
        ));

        assertEquals(Map.of("risk_level", "severity"), mappingPlan.fieldMappings());
        assertEquals(1, mappingPlan.fieldMappingDetails().size());
        assertEquals("lower", mappingPlan.fieldMappingDetails().get(0).transformRule());
        assertEquals("valueMap", mappingPlan.fieldMappingDetails().get(0).transformRulePayload().get("type"));
    }

    @Test
    void mappingPlanJsonKeepsOldAndNewWireCompatibility() throws Exception {
        var oldJson = """
            {
              "fieldMappings": {"id": "externalId"},
              "dedupFields": ["id"]
            }
            """;
        var oldPlan = objectMapper.readValue(oldJson, TransformMappingPlanDto.class);
        assertEquals(Map.of("id", "externalId"), oldPlan.fieldMappings());
        assertEquals(List.of("id"), oldPlan.dedupFields());
        assertEquals(List.of(), oldPlan.fieldMappingDetails());

        var newJson = """
            {
              "fieldMappings": {"id": "externalId"},
              "dedupFields": ["id"],
              "fieldMappingDetails": [
                {
                  "sourceField": "user_account",
                  "standardField": "actor",
                  "transformRule": " lower ",
                  "transformRulePayload": {
                    "type": "valueMap",
                    "values": {
                      "ADMIN": "admin"
                    },
                    "onMissing": "keepOriginal"
                  }
                }
              ]
            }
            """;
        var newPlan = objectMapper.readValue(newJson, TransformMappingPlanDto.class);
        assertEquals(Map.of("id", "externalId"), newPlan.fieldMappings());
        assertEquals(List.of("id"), newPlan.dedupFields());
        assertEquals(1, newPlan.fieldMappingDetails().size());
        assertEquals(" lower ", newPlan.fieldMappingDetails().get(0).transformRule());
        assertEquals("valueMap", newPlan.fieldMappingDetails().get(0).transformRulePayload().get("type"));
        assertEquals(Map.of("ADMIN", "admin"), newPlan.fieldMappingDetails().get(0).transformRulePayload().get("values"));

        var explicitNullJson = """
            {
              "fieldMappings": {"id": "externalId"},
              "dedupFields": ["id"],
              "fieldMappingDetails": null
            }
            """;
        var explicitNullPlan = objectMapper.readValue(explicitNullJson, TransformMappingPlanDto.class);
        assertEquals(Map.of("id", "externalId"), explicitNullPlan.fieldMappings());
        assertEquals(List.of("id"), explicitNullPlan.dedupFields());
        assertEquals(List.of(), explicitNullPlan.fieldMappingDetails());
    }

    @Test
    void transformsSingleRowWithFieldMappingDetailsAndAppliesBasicRules() throws Exception {
        mockMvc.perform(post("/api/transform/standard-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "row": {
                        "id": "ALERT-1",
                        "create_time": "2026-05-20 10:30:00",
                        "event_name": "sensitive file export",
                        "user_account": "USER_A",
                        "host_name": " WIN-01 ",
                        "risk_level": "HIGH",
                        "action_raw": ""
                      },
                      "mappingPlan": {
                        "fieldMappings": {
                          "id": "externalId",
                          "create_time": "occurredAt",
                          "event_name": "title",
                          "user_account": "actor",
                          "host_name": "assetRef",
                          "action_raw": "action",
                          "risk_level": "severity"
                        },
                        "dedupFields": ["id"],
                        "fieldMappingDetails": [
                          {
                            "sourceField": "user_account",
                            "standardField": "actor",
                            "transformRule": "lower",
                            "transformRulePayload": {
                              "type": "valueMap",
                              "values": {
                                "USER_A": "ignored"
                              }
                            }
                          },
                          {
                            "sourceField": "host_name",
                            "standardField": "assetRef",
                            "transformRule": "trim",
                            "transformRulePayload": {
                              "type": "valueMap",
                              "values": {
                                " WIN-01 ": "ignored"
                              }
                            }
                          },
                          {
                            "sourceField": "event_name",
                            "standardField": "title",
                            "transformRule": "upper"
                          },
                          {
                            "sourceField": "action_raw",
                            "standardField": "action",
                            "transformRule": "defaultIfBlank:LOGIN"
                          }
                        ]
                      },
                      "options": {
                        "dataSourceId": 7,
                        "schemaTableId": 11,
                        "sourceTable": "sec_alert_event",
                        "syncMode": "sync_once"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.draft.externalId").value("ALERT-1"))
            .andExpect(jsonPath("$.draft.eventType").value("SENSITIVE FILE EXPORT"))
            .andExpect(jsonPath("$.draft.actor").value("user_a"))
            .andExpect(jsonPath("$.draft.assetRef").value("WIN-01"))
            .andExpect(jsonPath("$.draft.action").value("LOGIN"))
            .andExpect(jsonPath("$.draft.severity").value("high"))
            .andExpect(jsonPath("$.draft.normalized.mapped.actor").value("user_a"))
            .andExpect(jsonPath("$.draft.normalized.mapped.assetRef").value("WIN-01"))
            .andExpect(jsonPath("$.draft.normalized.mapped.action").value("LOGIN"))
            .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void transformsBatchRowsWithFieldMappingDetailsInInputOrder() throws Exception {
        mockMvc.perform(post("/api/transform/standard-events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "rows": [
                        {"id": "ALERT-1", "create_time": "2026-05-20 10:30:00", "user_account": "USER_A", "risk_level": "HIGH"},
                        {"id": "ALERT-2", "create_time": "2026-05-20 10:31:00", "user_account": "USER_B", "risk_level": "low"}
                      ],
                      "mappingPlan": {
                        "fieldMappings": {
                          "id": "externalId",
                          "create_time": "occurredAt",
                          "user_account": "actor",
                          "risk_level": "severity"
                        },
                        "dedupFields": ["id"],
                        "fieldMappingDetails": [
                          {
                            "sourceField": "user_account",
                            "standardField": "actor",
                            "transformRule": "lower",
                            "transformRulePayload": {
                              "type": "valueMap",
                              "values": {
                                "USER_A": "ignored"
                              }
                            }
                          }
                        ]
                      },
                      "options": {
                        "dataSourceId": 7,
                        "schemaTableId": 11,
                        "sourceTable": "sec_alert_event",
                        "syncMode": "sync_once"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].index").value(0))
            .andExpect(jsonPath("$.results[0].draft.externalId").value("ALERT-1"))
            .andExpect(jsonPath("$.results[0].draft.actor").value("user_a"))
            .andExpect(jsonPath("$.results[0].draft.severity").value("high"))
            .andExpect(jsonPath("$.results[1].index").value(1))
            .andExpect(jsonPath("$.results[1].draft.externalId").value("ALERT-2"))
            .andExpect(jsonPath("$.results[1].draft.actor").value("user_b"))
            .andExpect(jsonPath("$.results[1].draft.severity").value("low"))
            .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void valueMapAndInvalidTransformRulesReturnWarningsWithoutHttpFailure() throws Exception {
        mockMvc.perform(post("/api/transform/standard-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "row": {
                        "id": "ALERT-1",
                        "create_time": "2026-05-20 10:30:00",
                        "user_account": "USER_A",
                        "risk_level": "HIGH"
                      },
                      "mappingPlan": {
                        "fieldMappings": {
                          "id": "externalId",
                          "create_time": "occurredAt",
                          "user_account": "actor",
                          "risk_level": "severity"
                        },
                        "dedupFields": ["id"],
                        "fieldMappingDetails": [
                          {
                            "sourceField": "user_account",
                            "standardField": "actor",
                            "transformRule": "valueMap",
                            "transformRulePayload": {
                              "type": "valueMap",
                              "values": {
                                "USER_A": "mapped-user"
                              },
                              "onMissing": "keepOriginal"
                            }
                          },
                          {
                            "sourceField": "risk_level",
                            "standardField": "severity",
                            "transformRule": "defaultIfBlank"
                          }
                        ]
                      },
                      "options": {
                        "dataSourceId": 7,
                        "schemaTableId": 11,
                        "sourceTable": "sec_alert_event",
                        "syncMode": "sync_once"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.draft.actor").value("mapped-user"))
            .andExpect(jsonPath("$.draft.severity").value("high"))
            .andExpect(jsonPath("$.errors.length()").value(0))
            .andExpect(jsonPath("$.warnings[0]").value("transform_rule_invalid"));
    }

    @Test
    void transformsBatchRowsWithValueMapInInputOrder() throws Exception {
        mockMvc.perform(post("/api/transform/standard-events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "rows": [
                        {"id": "ALERT-1", "create_time": "2026-05-20 10:30:00", "user_account": "USER_A", "risk_level": "warn"},
                        {"id": "ALERT-2", "create_time": "2026-05-20 10:31:00", "user_account": "USER_B", "risk_level": "critical"}
                      ],
                      "mappingPlan": {
                        "fieldMappings": {
                          "id": "externalId",
                          "create_time": "occurredAt",
                          "user_account": "actor",
                          "risk_level": "severity"
                        },
                        "dedupFields": ["id"],
                        "fieldMappingDetails": [
                          {
                            "sourceField": "risk_level",
                            "standardField": "severity",
                            "transformRule": "valueMap",
                            "transformRulePayload": {
                              "type": "valueMap",
                              "values": {
                                "critical": "high",
                                "warn": "medium"
                              },
                              "onMissing": "keepOriginal"
                            }
                          }
                        ]
                      },
                      "options": {
                        "dataSourceId": 7,
                        "schemaTableId": 11,
                        "sourceTable": "sec_alert_event",
                        "syncMode": "sync_once"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].index").value(0))
            .andExpect(jsonPath("$.results[0].draft.externalId").value("ALERT-1"))
            .andExpect(jsonPath("$.results[0].draft.severity").value("medium"))
            .andExpect(jsonPath("$.results[1].index").value(1))
            .andExpect(jsonPath("$.results[1].draft.externalId").value("ALERT-2"))
            .andExpect(jsonPath("$.results[1].draft.severity").value("high"))
            .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void transformsSingleRowWithDraftErrorsAndWarnings() throws Exception {
        mockMvc.perform(post("/api/transform/standard-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "row": {
                        "id": "ALERT-1",
                        "create_time": "2026-05-20 10:30:00",
                        "event_name": "Sensitive file export",
                        "user_account": "zhangsan",
                        "host_name": "WIN-01",
                        "risk_level": "high"
                      },
                      "mappingPlan": {
                        "fieldMappings": {
                          "id": "externalId",
                          "create_time": "occurredAt",
                          "event_name": "title",
                          "user_account": "actor",
                          "host_name": "assetRef",
                          "risk_level": "severity"
                        },
                        "dedupFields": ["id"]
                      },
                      "options": {
                        "dataSourceId": 7,
                        "schemaTableId": 11,
                        "sourceTable": "sec_alert_event",
                        "syncMode": "sync_once"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.draft.sourceSystem").value("ds:7:st:11"))
            .andExpect(jsonPath("$.draft.externalId").value("ALERT-1"))
            .andExpect(jsonPath("$.draft.occurredAt").value("2026-05-20T10:30+08:00"))
            .andExpect(jsonPath("$.draft.severity").value("high"))
            .andExpect(jsonPath("$.draft.riskScore").value(80))
            .andExpect(jsonPath("$.errors.length()").value(0))
            .andExpect(jsonPath("$.warnings.length()").value(0));
    }

    @Test
    void transformsBatchRowsInInputOrder() throws Exception {
        mockMvc.perform(post("/api/transform/standard-events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "rows": [
                        {"id": "ALERT-1", "create_time": "2026-05-20 10:30:00"},
                        {"id": "ALERT-2", "create_time": "2026-05-20 10:31:00"}
                      ],
                      "mappingPlan": {
                        "fieldMappings": {
                          "id": "externalId",
                          "create_time": "occurredAt"
                        },
                        "dedupFields": ["id"]
                      },
                      "options": {
                        "dataSourceId": 7,
                        "schemaTableId": 11,
                        "sourceTable": "sec_alert_event",
                        "syncMode": "sync_once"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].index").value(0))
            .andExpect(jsonPath("$.results[0].draft.externalId").value("ALERT-1"))
            .andExpect(jsonPath("$.results[1].index").value(1))
            .andExpect(jsonPath("$.results[1].draft.externalId").value("ALERT-2"))
            .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void rejectsInvalidBatchRequests() throws Exception {
        mockMvc.perform(post("/api/transform/standard-events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(status().reason("invalid_transform_request"));

        var rows = new StringBuilder("[");
        for (var index = 0; index < 101; index++) {
            if (index > 0) {
                rows.append(',');
            }
            rows.append("{\"id\":\"ALERT-").append(index).append("\"}");
        }
        rows.append(']');

        mockMvc.perform(post("/api/transform/standard-events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":" + rows + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(status().reason("batch_too_large"));
    }

    @Test
    void transformResponsesDoNotExposeDatabaseConfigOrSecrets() throws Exception {
        var result = mockMvc.perform(post("/api/transform/standard-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "row": {
                        "id": "ALERT-1",
                        "create_time": "2026-05-20 10:30:00",
                        "password": "SHOULD_NOT_APPEAR",
                        "authorization": "Bearer SHOULD_NOT_APPEAR"
                      },
                      "mappingPlan": {
                        "fieldMappings": {
                          "id": "externalId",
                          "create_time": "occurredAt"
                        },
                        "dedupFields": ["id"]
                      },
                      "options": {
                        "dataSourceId": 7,
                        "schemaTableId": 11,
                        "sourceTable": "sec_alert_event",
                        "syncMode": "sync_once"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();

        var body = result.getResponse().getContentAsString();
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("SHOULD_NOT_APPEAR")));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("authorization")));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("password")));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("jdbc:")));
    }
}
