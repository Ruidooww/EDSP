package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.SchemaChangeActionRequest;
import com.edsp.core.dto.SchemaScanExecuteRequest;
import com.edsp.core.dto.SchemaScanFinishRequest;
import com.edsp.core.dto.SchemaScanRunRequest;
import com.edsp.core.service.SchemaScanService;
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
@RequestMapping("/api/core/schema-scans")
public class SchemaScanController {
    private final SchemaScanService schemaScanService;

    public SchemaScanController(SchemaScanService schemaScanService) {
        this.schemaScanService = schemaScanService;
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(schemaScanService.runs(limit));
    }

    @PostMapping("/runs")
    public ApiResponse<Map<String, Object>> createRun(@RequestBody SchemaScanRunRequest request) {
        return ApiResponse.ok(schemaScanService.createRun(request), "created");
    }

    @GetMapping("/changes")
    public ApiResponse<List<Map<String, Object>>> changes(
        @RequestParam(name = "limit", defaultValue = "100") int limit,
        @RequestParam(name = "status", defaultValue = "") String status,
        @RequestParam(name = "scanRunId", required = false) Long scanRunId
    ) {
        return ApiResponse.ok(schemaScanService.changes(limit, status, scanRunId));
    }

    @PutMapping("/changes/{id}/status")
    public ApiResponse<Map<String, Object>> updateChangeStatus(
        @PathVariable("id") long id,
        @RequestBody SchemaChangeActionRequest request
    ) {
        return ApiResponse.ok(schemaScanService.updateChangeStatus(id, request), "updated");
    }

    @PostMapping("/changes/batch-status")
    public ApiResponse<Map<String, Object>> updateChangeStatusBatch(
        @RequestBody SchemaChangeActionRequest request
    ) {
        return ApiResponse.ok(schemaScanService.updateChangeStatusBatch(request), "updated");
    }

    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> execute(@RequestBody SchemaScanExecuteRequest request) {
        var result = schemaScanService.execute(request);
        var message = "success".equals(result.get("status")) ? "scanned" : "failed";
        return ApiResponse.ok(result, message);
    }

    @PutMapping("/runs/{id}/finish")
    public ApiResponse<Map<String, Object>> finishRun(
        @PathVariable("id") long id,
        @RequestBody SchemaScanFinishRequest request
    ) {
        return ApiResponse.ok(schemaScanService.finishRun(id, request), "finished");
    }

    @GetMapping("/runs/{id}/tables")
    public ApiResponse<List<Map<String, Object>>> tables(@PathVariable("id") long id) {
        return ApiResponse.ok(schemaScanService.tables(id));
    }
}
