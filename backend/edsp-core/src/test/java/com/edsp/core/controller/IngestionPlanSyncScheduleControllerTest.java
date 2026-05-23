package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.dto.IngestionPlanSyncScheduleRequest;
import com.edsp.core.service.IngestionPlanSyncScheduleService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class IngestionPlanSyncScheduleControllerTest {
    @Test
    void scheduleRoutesUseStableResourcePaths() throws Exception {
        Method create = IngestionPlanActivationController.class.getMethod(
            "createSyncSchedule",
            long.class,
            IngestionPlanSyncScheduleRequest.class
        );
        assertArrayEquals(new String[] {"/{activationId}/sync-schedules"}, create.getAnnotation(PostMapping.class).value());

        Method list = IngestionPlanController.class.getMethod("syncSchedules", long.class, int.class);
        assertArrayEquals(new String[] {"/{id}/sync-schedules"}, list.getAnnotation(GetMapping.class).value());

        var mapping = IngestionPlanSyncScheduleController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core/ingestion-plan-sync-schedules"}, mapping.value());

        Method update = IngestionPlanSyncScheduleController.class.getMethod(
            "update",
            long.class,
            IngestionPlanSyncScheduleRequest.class
        );
        assertArrayEquals(new String[] {"/{scheduleId}"}, update.getAnnotation(PutMapping.class).value());

        Method pause = IngestionPlanSyncScheduleController.class.getMethod(
            "pause",
            long.class,
            IngestionPlanSyncScheduleRequest.class
        );
        assertArrayEquals(new String[] {"/{scheduleId}/pause"}, pause.getAnnotation(PostMapping.class).value());

        Method resume = IngestionPlanSyncScheduleController.class.getMethod(
            "resume",
            long.class,
            IngestionPlanSyncScheduleRequest.class
        );
        assertArrayEquals(new String[] {"/{scheduleId}/resume"}, resume.getAnnotation(PostMapping.class).value());
    }

    @Test
    void controllersDelegateScheduleOperations() {
        var scheduleService = new StubScheduleService();
        var activationController = new IngestionPlanActivationController(null, null, scheduleService);
        var planController = new IngestionPlanController(null, null, null, null, scheduleService);
        var scheduleController = new IngestionPlanSyncScheduleController(scheduleService);

        var created = activationController.createSyncSchedule(
            9L,
            new IngestionPlanSyncScheduleRequest(300, 50, "ops")
        );
        var listed = planController.syncSchedules(7L, 3);
        var updated = scheduleController.update(11L, new IngestionPlanSyncScheduleRequest(600, 20, "ops"));
        var paused = scheduleController.pause(11L, new IngestionPlanSyncScheduleRequest(null, null, "ops"));
        var resumed = scheduleController.resume(11L, new IngestionPlanSyncScheduleRequest(null, null, "ops"));

        assertEquals(9L, created.data().get("activationId"));
        assertEquals(7L, listed.data().get(0).get("ingestionPlanId"));
        assertEquals(600, updated.data().get("intervalSeconds"));
        assertEquals("paused", paused.data().get("status"));
        assertEquals("enabled", resumed.data().get("status"));
        assertEquals(11L, scheduleService.scheduleId);
    }

    private static class StubScheduleService extends IngestionPlanSyncScheduleService {
        private long scheduleId;

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

        @Override
        public Map<String, Object> update(long scheduleId, IngestionPlanSyncScheduleRequest request) {
            this.scheduleId = scheduleId;
            return Map.of("id", scheduleId, "intervalSeconds", request.intervalSeconds());
        }

        @Override
        public Map<String, Object> pause(long scheduleId, IngestionPlanSyncScheduleRequest request) {
            this.scheduleId = scheduleId;
            return Map.of("id", scheduleId, "status", "paused");
        }

        @Override
        public Map<String, Object> resume(long scheduleId, IngestionPlanSyncScheduleRequest request) {
            this.scheduleId = scheduleId;
            return Map.of("id", scheduleId, "status", "enabled");
        }
    }
}
