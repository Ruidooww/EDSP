package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.service.IngestionPlanShadowRunService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ingestion-plan-shadow-runs")
public class IngestionPlanShadowRunController {
    private final IngestionPlanShadowRunService shadowRunService;

    public IngestionPlanShadowRunController(IngestionPlanShadowRunService shadowRunService) {
        this.shadowRunService = shadowRunService;
    }

    @GetMapping("/{runId}")
    public ApiResponse<Map<String, Object>> shadowRunDetail(@PathVariable("runId") long runId) {
        return ApiResponse.ok(shadowRunService.shadowRunDetail(runId));
    }
}
