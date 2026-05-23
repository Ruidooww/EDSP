package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.IngestionPlanActivationRequest;
import com.edsp.core.dto.IngestionPlanSyncOnceRequest;
import com.edsp.core.dto.IngestionPlanSyncScheduleRequest;
import com.edsp.core.service.IngestionPlanActivationService;
import com.edsp.core.service.IngestionPlanSyncOnceService;
import com.edsp.core.service.IngestionPlanSyncScheduleService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ingestion-plan-activations")
public class IngestionPlanActivationController {
    private final IngestionPlanActivationService activationService;
    private final IngestionPlanSyncOnceService syncOnceService;
    private final IngestionPlanSyncScheduleService scheduleService;

    public IngestionPlanActivationController(
        IngestionPlanActivationService activationService,
        IngestionPlanSyncOnceService syncOnceService,
        IngestionPlanSyncScheduleService scheduleService
    ) {
        this.activationService = activationService;
        this.syncOnceService = syncOnceService;
        this.scheduleService = scheduleService;
    }

    @PostMapping("/{activationId}/deactivate")
    public ApiResponse<Map<String, Object>> deactivate(
        @PathVariable("activationId") long activationId,
        @RequestBody(required = false) IngestionPlanActivationRequest request
    ) {
        return ApiResponse.ok(activationService.deactivate(activationId, request), "deactivated");
    }

    @PostMapping("/{activationId}/sync-once")
    public ApiResponse<Map<String, Object>> syncOnce(
        @PathVariable("activationId") long activationId,
        @RequestBody(required = false) IngestionPlanSyncOnceRequest request
    ) {
        return ApiResponse.ok(syncOnceService.syncOnce(activationId, request), "synced");
    }

    @PostMapping("/{activationId}/sync-schedules")
    public ApiResponse<Map<String, Object>> createSyncSchedule(
        @PathVariable("activationId") long activationId,
        @RequestBody(required = false) IngestionPlanSyncScheduleRequest request
    ) {
        return ApiResponse.ok(scheduleService.createSchedule(activationId, request), "created");
    }
}
