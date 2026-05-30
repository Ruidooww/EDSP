package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.IngestionPlanActivationRequest;
import com.edsp.core.dto.IngestionPlanGenerateRequest;
import com.edsp.core.dto.IngestionPlanMappingRuleUpdateRequest;
import com.edsp.core.dto.IngestionPlanShadowRunRequest;
import com.edsp.core.dto.IngestionPlanShadowValidationRequest;
import com.edsp.core.dto.IngestionPlanStatusRequest;
import com.edsp.core.service.IngestionPlanActivationService;
import com.edsp.core.service.IngestionPlanShadowRunService;
import com.edsp.core.service.IngestionPlanService;
import com.edsp.core.service.IngestionPlanSyncOnceService;
import com.edsp.core.service.IngestionPlanSyncScheduleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ingestion-plans")
public class IngestionPlanController {
    private final IngestionPlanService ingestionPlanService;
    private final IngestionPlanShadowRunService shadowRunService;
    private final IngestionPlanActivationService activationService;
    private final IngestionPlanSyncOnceService syncOnceService;
    private final IngestionPlanSyncScheduleService scheduleService;

    public IngestionPlanController(
        IngestionPlanService ingestionPlanService,
        IngestionPlanShadowRunService shadowRunService,
        IngestionPlanActivationService activationService,
        IngestionPlanSyncOnceService syncOnceService,
        IngestionPlanSyncScheduleService scheduleService
    ) {
        this.ingestionPlanService = ingestionPlanService;
        this.shadowRunService = shadowRunService;
        this.activationService = activationService;
        this.syncOnceService = syncOnceService;
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> plans(
        @RequestParam(name = "dataSourceId", required = false) Long dataSourceId,
        @RequestParam(name = "status", required = false) String status
    ) {
        return ApiResponse.ok(ingestionPlanService.list(dataSourceId, status));
    }

    @PostMapping("/generate")
    public ApiResponse<List<Map<String, Object>>> generate(@RequestBody IngestionPlanGenerateRequest request) {
        return ApiResponse.ok(ingestionPlanService.generate(request), "generated");
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> updateStatus(
        @PathVariable("id") long id,
        @RequestBody IngestionPlanStatusRequest request
    ) {
        return ApiResponse.ok(ingestionPlanService.updateStatus(id, request), "updated");
    }

    @PutMapping("/{id}/mapping-rules")
    public ApiResponse<Map<String, Object>> updateMappingRule(
        @PathVariable("id") long id,
        @Valid @RequestBody IngestionPlanMappingRuleUpdateRequest request
    ) {
        return ApiResponse.ok(ingestionPlanService.updateMappingRule(id, request), "updated");
    }

    @PostMapping("/{id}/shadow-validate")
    public ApiResponse<Map<String, Object>> shadowValidate(
        @PathVariable("id") long id,
        @RequestBody(required = false) IngestionPlanShadowValidationRequest request
    ) {
        return ApiResponse.ok(ingestionPlanService.shadowValidate(id, request), "validated");
    }

    @PostMapping("/{id}/shadow-runs")
    public ApiResponse<Map<String, Object>> createShadowRun(
        @PathVariable("id") long id,
        @Valid @RequestBody(required = false) IngestionPlanShadowRunRequest request
    ) {
        return ApiResponse.ok(shadowRunService.createShadowRun(id, request), "created");
    }

    @GetMapping("/{id}/shadow-runs")
    public ApiResponse<List<Map<String, Object>>> shadowRuns(
        @PathVariable("id") long id,
        @RequestParam(name = "limit", defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(shadowRunService.listShadowRuns(id, limit));
    }

    @PostMapping("/{id}/activations")
    public ApiResponse<Map<String, Object>> activate(
        @PathVariable("id") long id,
        @RequestBody(required = false) IngestionPlanActivationRequest request
    ) {
        return ApiResponse.ok(activationService.activate(id, request), "created");
    }

    @GetMapping("/{id}/activations")
    public ApiResponse<List<Map<String, Object>>> activations(
        @PathVariable("id") long id,
        @RequestParam(name = "limit", defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(activationService.list(id, limit));
    }

    @GetMapping("/{id}/sync-runs")
    public ApiResponse<List<Map<String, Object>>> syncRuns(
        @PathVariable("id") long id,
        @RequestParam(name = "limit", defaultValue = "5") int limit
    ) {
        return ApiResponse.ok(syncOnceService.listByPlan(id, limit));
    }

    @GetMapping("/{id}/sync-schedules")
    public ApiResponse<List<Map<String, Object>>> syncSchedules(
        @PathVariable("id") long id,
        @RequestParam(name = "limit", defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(scheduleService.listByPlan(id, limit));
    }
}
