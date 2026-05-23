package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.IngestionPlanSyncScheduleRequest;
import com.edsp.core.service.IngestionPlanSyncScheduleService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ingestion-plan-sync-schedules")
public class IngestionPlanSyncScheduleController {
    private final IngestionPlanSyncScheduleService scheduleService;

    public IngestionPlanSyncScheduleController(IngestionPlanSyncScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PutMapping("/{scheduleId}")
    public ApiResponse<Map<String, Object>> update(
        @PathVariable("scheduleId") long scheduleId,
        @RequestBody(required = false) IngestionPlanSyncScheduleRequest request
    ) {
        return ApiResponse.ok(scheduleService.update(scheduleId, request), "updated");
    }

    @PostMapping("/{scheduleId}/pause")
    public ApiResponse<Map<String, Object>> pause(
        @PathVariable("scheduleId") long scheduleId,
        @RequestBody(required = false) IngestionPlanSyncScheduleRequest request
    ) {
        return ApiResponse.ok(scheduleService.pause(scheduleId, request), "paused");
    }

    @PostMapping("/{scheduleId}/resume")
    public ApiResponse<Map<String, Object>> resume(
        @PathVariable("scheduleId") long scheduleId,
        @RequestBody(required = false) IngestionPlanSyncScheduleRequest request
    ) {
        return ApiResponse.ok(scheduleService.resume(scheduleId, request), "resumed");
    }
}
