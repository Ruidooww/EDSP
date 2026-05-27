package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.IngestionPlanActivationRequest;
import com.edsp.core.dto.IngestionPlanSyncOnceRequest;
import com.edsp.core.dto.IngestionPlanSyncScheduleRequest;
import com.edsp.core.service.IngestionPlanActivationService;
import com.edsp.core.service.IngestionPlanSyncOnceService;
import com.edsp.core.service.IngestionPlanSyncScheduleService;
import com.edsp.core.transform.runtime.TransformBatchResult;
import com.edsp.core.transform.runtime.TransformRuntimeClient;
import com.edsp.transform.contract.BatchTransformRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class IngestionPlanActivationControllerTest {
    @Test
    void activationRoutesUseStableResourcePaths() throws Exception {
        var planRequestMapping = IngestionPlanController.class.getAnnotation(RequestMapping.class);
        assertEquals("/api/core/ingestion-plans", planRequestMapping.value()[0]);

        Method create = IngestionPlanController.class.getMethod(
            "activate",
            long.class,
            IngestionPlanActivationRequest.class
        );
        assertEquals("/{id}/activations", create.getAnnotation(PostMapping.class).value()[0]);

        Method list = IngestionPlanController.class.getMethod("activations", long.class, int.class);
        assertEquals("/{id}/activations", list.getAnnotation(GetMapping.class).value()[0]);

        var activationRequestMapping = IngestionPlanActivationController.class.getAnnotation(RequestMapping.class);
        assertEquals("/api/core/ingestion-plan-activations", activationRequestMapping.value()[0]);

        Method deactivate = IngestionPlanActivationController.class.getMethod(
            "deactivate",
            long.class,
            IngestionPlanActivationRequest.class
        );
        assertEquals("/{activationId}/deactivate", deactivate.getAnnotation(PostMapping.class).value()[0]);
    }

    @Test
    void controllerMethodsReturnServicePayloads() {
        var activationService = new StubActivationService();
        var syncOnceService = new StubSyncOnceService();
        var scheduleService = new StubScheduleService();
        var planController = new IngestionPlanController(null, null, activationService, syncOnceService, scheduleService);
        var activationController = new IngestionPlanActivationController(activationService, syncOnceService, scheduleService);

        var created = planController.activate(7L, new IngestionPlanActivationRequest(9L, "ops", "validated"));
        var list = planController.activations(7L, 10);
        var deactivated = activationController.deactivate(
            11L,
            new IngestionPlanActivationRequest(null, "ops", "rollback")
        );

        assertTrue(created.success());
        assertEquals(7L, created.data().get("ingestionPlanId"));
        assertEquals("active", created.data().get("status"));
        assertEquals(1, list.data().size());
        assertEquals(7L, list.data().get(0).get("ingestionPlanId"));
        assertEquals("deactivated", deactivated.data().get("status"));
    }

    private static class StubActivationService extends IngestionPlanActivationService {
        StubActivationService() {
            super(null, null, null);
        }

        @Override
        public Map<String, Object> activate(long planId, IngestionPlanActivationRequest request) {
            return Map.of("ingestionPlanId", planId, "shadowRunId", request.shadowRunId(), "status", "active");
        }

        @Override
        public List<Map<String, Object>> list(long planId, int limit) {
            return List.of(Map.of("ingestionPlanId", planId, "status", "active"));
        }

        @Override
        public Map<String, Object> deactivate(long activationId, IngestionPlanActivationRequest request) {
            return Map.of("id", activationId, "status", "deactivated");
        }
    }

    private static class StubSyncOnceService extends IngestionPlanSyncOnceService {
        StubSyncOnceService() {
            super(null, null, null, null, null, null, new NoopTransformRuntimeClient());
        }

        @Override
        public Map<String, Object> syncOnce(long activationId, IngestionPlanSyncOnceRequest request) {
            return Map.of("id", activationId, "status", "passed");
        }
    }

    private static class NoopTransformRuntimeClient implements TransformRuntimeClient {
        @Override
        public String mode() {
            return "local";
        }

        @Override
        public TransformBatchResult transform(BatchTransformRequest request) {
            throw new UnsupportedOperationException("stub");
        }
    }

    private static class StubScheduleService extends IngestionPlanSyncScheduleService {
        StubScheduleService() {
            super(null, null, null, null);
        }

        @Override
        public Map<String, Object> createSchedule(long activationId, IngestionPlanSyncScheduleRequest request) {
            return Map.of("activationId", activationId, "status", "enabled");
        }

        @Override
        public List<Map<String, Object>> listByPlan(long planId, int limit) {
            return List.of(Map.of("ingestionPlanId", planId, "status", "enabled"));
        }
    }
}
