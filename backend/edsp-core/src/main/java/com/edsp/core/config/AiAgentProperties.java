package com.edsp.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiAgentProperties {
    private final String baseUrl;
    private final int timeoutMs;
    private final boolean fallbackEnabled;

    public AiAgentProperties(
        @Value("${edsp.ai.agent.base-url:http://127.0.0.1:18090}") String baseUrl,
        @Value("${edsp.ai.agent.timeout-ms:8000}") int timeoutMs,
        @Value("${edsp.ai.agent.fallback-enabled:true}") boolean fallbackEnabled
    ) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
        this.fallbackEnabled = fallbackEnabled;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public boolean fallbackEnabled() {
        return fallbackEnabled;
    }
}

