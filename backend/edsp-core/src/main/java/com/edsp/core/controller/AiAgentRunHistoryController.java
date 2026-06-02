package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.service.AiAgentRunHistoryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ai-agents/runs")
public class AiAgentRunHistoryController {
    private final AiAgentRunHistoryService service;

    public AiAgentRunHistoryController(AiAgentRunHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AiAgentRunHistoryService.HistoryRow>> list(
        @RequestParam(name = "limit", defaultValue = "20") int limit,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "source", required = false) String source,
        @RequestParam(name = "providerKey", required = false) String providerKey,
        @RequestParam(name = "theme", required = false) String theme,
        @RequestParam(name = "period", required = false) String period
    ) {
        return ApiResponse.ok(service.list(new AiAgentRunHistoryService.HistoryFilter(
            limit, status, source, providerKey, theme, period
        )));
    }

    @GetMapping("/{id}")
    public ApiResponse<AiAgentRunHistoryService.HistoryDetail> detail(@PathVariable("id") long id) {
        return ApiResponse.ok(service.detail(id));
    }
}
