package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.service.AiAgentProviderConfigService;
import com.edsp.core.service.PythonAiAgentClient;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/ai-agent-provider-configs")
public class AiAgentProviderConfigController {
    private final AiAgentProviderConfigService service;

    public AiAgentProviderConfigController(AiAgentProviderConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AiAgentProviderConfigService.ProviderConfigRow>> list() {
        return ApiResponse.ok(service.listConfigs());
    }

    @PostMapping("/{providerKey}/test")
    public ApiResponse<PythonAiAgentClient.ProviderTestResult> test(@PathVariable("providerKey") String providerKey) {
        return ApiResponse.ok(service.testProvider(providerKey));
    }
}
