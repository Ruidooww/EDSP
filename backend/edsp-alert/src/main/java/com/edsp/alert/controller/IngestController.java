package com.edsp.alert.controller;

import com.edsp.alert.dto.IngestAlertRequest;
import com.edsp.alert.service.AlertIngestService;
import com.edsp.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {
    private final AlertIngestService alertIngestService;

    public IngestController(AlertIngestService alertIngestService) {
        this.alertIngestService = alertIngestService;
    }

    @PostMapping("/alerts")
    public ApiResponse<Map<String, Object>> ingestAlert(@Valid @RequestBody IngestAlertRequest request) {
        return ApiResponse.ok(alertIngestService.ingest(request));
    }

    @PostMapping("/alerts/batch")
    public ApiResponse<Map<String, Object>> ingestAlerts(@RequestBody List<@Valid IngestAlertRequest> requests) {
        return ApiResponse.ok(alertIngestService.ingestBatch(requests));
    }
}
