package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class IngestionPlanSyncOnceControllerTest {
    @Test
    void syncOnceIsExposedOnlyThroughActivationResource() throws Exception {
        var activationMapping = IngestionPlanActivationController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core/ingestion-plan-activations"}, activationMapping.value());

        Method syncOnce = IngestionPlanActivationController.class.getMethod(
            "syncOnce",
            long.class,
            IngestionPlanSyncOnceRequest.class
        );
        var postMapping = syncOnce.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/{activationId}/sync-once"}, postMapping.value());

        for (var method : IngestionPlanController.class.getDeclaredMethods()) {
            var directPlanPost = method.getAnnotation(PostMapping.class);
            if (directPlanPost != null) {
                assertFalse(String.join(",", directPlanPost.value()).contains("sync-once"));
            }
        }
    }

    @Test
    void controllerDelegatesSyncOnceAndPlanRunList() {
        var syncService = new StubSyncOnceService();
        var scheduleService = new StubScheduleService();
        var activationController = new IngestionPlanActivationController(
            new StubActivationService(),
            syncService,
            scheduleService
        );
        var planController = new IngestionPlanController(null, null, null, syncService, scheduleService);

        var created = activationController.syncOnce(9L, new IngestionPlanSyncOnceRequest(20, "ops"));
        assertEquals(9L, syncService.activationId);
        assertEquals(20, syncService.sampleLimit);
        assertEquals("ops", syncService.operatorName);
        assertEquals("passed", created.data().get("status"));

        var listed = planController.syncRuns(7L, 3);
        assertEquals(7L, syncService.planId);
        assertEquals(3, syncService.limit);
        assertEquals(1, listed.data().size());
        assertEquals("warning", listed.data().get(0).get("status"));
    }

    @Test
    void planControllerOnlyAddsReadOnlySyncRunList() {
        assertTrue(List.of(IngestionPlanController.class.getDeclaredMethods()).stream().anyMatch(method ->
            method.getName().equals("syncRuns") && method.getAnnotation(GetMapping.class) != null
        ));
    }

    private static class StubSyncOnceService extends IngestionPlanSyncOnceService {
        private long activationId;
        private Integer sampleLimit;
        private String operatorName;
        private long planId;
        private int limit;

        StubSyncOnceService() {
            super(null, null, null, null, null, null, new NoopTransformRuntimeClient());
        }

        @Override
        public Map<String, Object> syncOnce(long activationId, IngestionPlanSyncOnceRequest request) {
            this.activationId = activationId;
            this.sampleLimit = request.sampleLimit();
            this.operatorName = request.operatorName();
            return Map.of("id", 11L, "status", "passed");
        }

        @Override
        public List<Map<String, Object>> listByPlan(long planId, int limit) {
            this.planId = planId;
            this.limit = limit;
            return List.of(Map.of("id", 12L, "status", "warning"));
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

    private static class StubActivationService extends IngestionPlanActivationService {
        StubActivationService() {
            super(null, null, null);
        }

        @Override
        public Map<String, Object> deactivate(long activationId, IngestionPlanActivationRequest request) {
            return Map.of("id", activationId);
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
