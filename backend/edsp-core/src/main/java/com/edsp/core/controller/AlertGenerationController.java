package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.AlertGenerationRunRequest;
import com.edsp.core.service.AlertGenerationService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/core/alert-generations")
public class AlertGenerationController {
    private final AlertGenerationService service;

    public AlertGenerationController(AlertGenerationService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.ok(service.generate(runRequest(request)), "generated");
    }

    private AlertGenerationRunRequest runRequest(Map<String, Object> request) {
        if (request == null) {
            return new AlertGenerationRunRequest(null);
        }
        if (!request.keySet().stream().allMatch("decisionId"::equals)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only decisionId is supported");
        }
        var rawDecisionId = request.get("decisionId");
        if (rawDecisionId == null) {
            return new AlertGenerationRunRequest(null);
        }
        if (rawDecisionId instanceof Number number) {
            return new AlertGenerationRunRequest(number.longValue());
        }
        try {
            return new AlertGenerationRunRequest(Long.parseLong(String.valueOf(rawDecisionId)));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decisionId must be numeric");
        }
    }
}
