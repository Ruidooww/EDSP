package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.service.AiAgentRunService;
import com.edsp.core.service.PythonAiAgentClient;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ai-agents")
public class AiAgentController {
    private final AiAgentRunService runService;
    private final PythonAiAgentClient pythonClient;

    public AiAgentController(AiAgentRunService runService, PythonAiAgentClient pythonClient) {
        this.runService = runService;
        this.pythonClient = pythonClient;
    }

    @GetMapping("/providers")
    public ApiResponse<List<Map<String, Object>>> providers() {
        return ApiResponse.ok(pythonClient.providers());
    }

    @PostMapping("/runs")
    public ApiResponse<PythonAiAgentClient.AgentResponse> run(@RequestBody AiAgentRunService.RunRequest request) {
        return ApiResponse.ok(runService.run(request));
    }

    @GetMapping("/runs/recent")
    public ApiResponse<List<Map<String, Object>>> recent(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ApiResponse.ok(runService.recent(limit));
    }
}

