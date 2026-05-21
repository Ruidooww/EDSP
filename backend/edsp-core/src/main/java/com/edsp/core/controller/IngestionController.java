package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.RawEventRequest;
import com.edsp.core.dto.StandardEventRequest;
import com.edsp.core.service.IngestionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ingestion")
public class IngestionController {
    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @GetMapping("/raw-events")
    public ApiResponse<List<Map<String, Object>>> rawEvents(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(ingestionService.rawEvents(limit));
    }

    @PostMapping("/raw-events")
    public ApiResponse<Map<String, Object>> createRawEvent(@Valid @RequestBody RawEventRequest request) {
        return ApiResponse.ok(ingestionService.createRawEvent(request), "created");
    }

    @GetMapping("/standard-events")
    public ApiResponse<List<Map<String, Object>>> standardEvents(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(ingestionService.standardEvents(limit));
    }

    @PostMapping("/standard-events")
    public ApiResponse<Map<String, Object>> createStandardEvent(
        @Valid @RequestBody StandardEventRequest request
    ) {
        var result = ingestionService.createStandardEvent(request);
        return ApiResponse.ok(result, String.valueOf(result.get("result")));
    }

    @PostMapping("/standard-events/from-raw/{rawEventId}")
    public ApiResponse<Map<String, Object>> standardizeRawEvent(
        @PathVariable("rawEventId") long rawEventId,
        @RequestBody StandardEventRequest request
    ) {
        var result = ingestionService.standardizeRawEvent(rawEventId, request);
        if (result == null) {
            return ApiResponse.fail("raw event not found");
        }
        return ApiResponse.ok(result, String.valueOf(result.get("result")));
    }
}
