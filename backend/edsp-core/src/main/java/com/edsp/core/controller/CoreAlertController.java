package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.service.AlertRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/alerts")
public class CoreAlertController {
    private final AlertRepository repository;

    public CoreAlertController(AlertRepository repository) {
        this.repository = repository;
    }

    @GetMapping("")
    public ApiResponse<List<Map<String, Object>>> list(
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "severity", required = false) String severity,
        @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(repository.list(status, severity, limit));
    }
}
