package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.AlertLifecycleRequest;
import com.edsp.core.service.AlertLifecycleService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/alerts")
public class AlertLifecycleController {
    private final AlertLifecycleService service;

    public AlertLifecycleController(AlertLifecycleService service) {
        this.service = service;
    }

    @PostMapping("/{id}/acknowledge")
    public ApiResponse<Map<String, Object>> acknowledge(
        @PathVariable("id") long id,
        @RequestBody(required = false) AlertLifecycleRequest request
    ) {
        return ApiResponse.ok(service.acknowledge(id, request), "acknowledged");
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<Map<String, Object>> assign(
        @PathVariable("id") long id,
        @RequestBody(required = false) AlertLifecycleRequest request
    ) {
        return ApiResponse.ok(service.assign(id, request), "assigned");
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Map<String, Object>> close(
        @PathVariable("id") long id,
        @RequestBody(required = false) AlertLifecycleRequest request
    ) {
        return ApiResponse.ok(service.close(id, request), "closed");
    }

    @GetMapping("/{id}/timeline")
    public ApiResponse<List<Map<String, Object>>> timeline(@PathVariable("id") long id) {
        return ApiResponse.ok(service.timeline(id));
    }
}
