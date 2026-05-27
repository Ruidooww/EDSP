package com.edsp.transformservice;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
