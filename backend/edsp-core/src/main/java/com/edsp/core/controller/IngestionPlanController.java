package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.IngestionPlanGenerateRequest;
import com.edsp.core.dto.IngestionPlanShadowValidationRequest;
import com.edsp.core.dto.IngestionPlanStatusRequest;
import com.edsp.core.service.IngestionPlanService;
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

    public IngestionPlanController(IngestionPlanService ingestionPlanService) {
        this.ingestionPlanService = ingestionPlanService;
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

    @PostMapping("/{id}/shadow-validate")
    public ApiResponse<Map<String, Object>> shadowValidate(
        @PathVariable("id") long id,
        @RequestBody(required = false) IngestionPlanShadowValidationRequest request
    ) {
        return ApiResponse.ok(ingestionPlanService.shadowValidate(id, request), "validated");
    }
}
