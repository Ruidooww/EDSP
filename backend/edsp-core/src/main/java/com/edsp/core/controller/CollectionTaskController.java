package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.CollectionTaskRequest;
import com.edsp.core.dto.IngestionRunFinishRequest;
import com.edsp.core.service.CollectionTaskService;
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
@RequestMapping("/api/core/collection-tasks")
public class CollectionTaskController {
    private final CollectionTaskService collectionTaskService;

    public CollectionTaskController(CollectionTaskService collectionTaskService) {
        this.collectionTaskService = collectionTaskService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(collectionTaskService.list(limit));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CollectionTaskRequest request) {
        return ApiResponse.ok(collectionTaskService.create(request), "created");
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
        @PathVariable("id") long id,
        @Valid @RequestBody CollectionTaskRequest request
    ) {
        return ApiResponse.ok(collectionTaskService.update(id, request), "updated");
    }

    @PostMapping("/{id}/runs")
    public ApiResponse<Map<String, Object>> startRun(
        @PathVariable("id") long id,
        @RequestParam(name = "runType", defaultValue = "manual") String runType
    ) {
        return ApiResponse.ok(collectionTaskService.startRun(id, runType), "started");
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(collectionTaskService.runs(limit));
    }

    @PutMapping("/runs/{runId}/finish")
    public ApiResponse<Map<String, Object>> finishRun(
        @PathVariable("runId") long runId,
        @RequestBody IngestionRunFinishRequest request
    ) {
        return ApiResponse.ok(collectionTaskService.finishRun(runId, request), "finished");
    }
}
