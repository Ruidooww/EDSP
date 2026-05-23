package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.service.IngestionPlanShadowRunService;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class IngestionPlanShadowRunControllerTest {
    @Test
    void detailRouteUsesStableShadowRunResourcePath() throws Exception {
        var requestMapping = IngestionPlanShadowRunController.class.getAnnotation(RequestMapping.class);
        assertEquals("/api/core/ingestion-plan-shadow-runs", requestMapping.value()[0]);

        Method method = IngestionPlanShadowRunController.class.getMethod("shadowRunDetail", long.class);
        var getMapping = method.getAnnotation(GetMapping.class);
        assertEquals("/{runId}", getMapping.value()[0]);
    }

    @Test
    void shadowRunDetailReturnsServicePayload() {
        var controller = new IngestionPlanShadowRunController(new StubShadowRunService());

        var response = controller.shadowRunDetail(42L);

        assertTrue(response.success());
        assertEquals(42L, response.data().get("id"));
        assertEquals("passed", response.data().get("status"));
    }

    private static class StubShadowRunService extends IngestionPlanShadowRunService {
        StubShadowRunService() {
            super(null, null, null, null, null);
        }

        @Override
        public Map<String, Object> shadowRunDetail(long runId) {
            return Map.of("id", runId, "status", "passed");
        }
    }
}
