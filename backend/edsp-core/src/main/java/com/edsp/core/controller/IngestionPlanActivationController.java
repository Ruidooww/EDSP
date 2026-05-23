package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.IngestionPlanActivationRequest;
import com.edsp.core.service.IngestionPlanActivationService;
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

    public IngestionPlanActivationController(IngestionPlanActivationService activationService) {
        this.activationService = activationService;
    }

    @PostMapping("/{activationId}/deactivate")
    public ApiResponse<Map<String, Object>> deactivate(
        @PathVariable("activationId") long activationId,
        @RequestBody(required = false) IngestionPlanActivationRequest request
    ) {
        return ApiResponse.ok(activationService.deactivate(activationId, request), "deactivated");
    }
}
