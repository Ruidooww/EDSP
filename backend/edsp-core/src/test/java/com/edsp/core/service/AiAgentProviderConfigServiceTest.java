package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.config.AiAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AiAgentProviderConfigServiceTest {
    @Test
    void mapsProviderDiscoveryToSanitizedCustomerReadinessRows() {
        var service = new AiAgentProviderConfigService(new StubPythonClient(new ObjectMapper()));

        var rows = service.listConfigs();

        var cloud = rows.stream()
            .filter(row -> "cloud-openai-compatible".equals(row.providerKey()))
            .findFirst()
            .orElseThrow();
        assertEquals("企业云模型", cloud.displayName());
        assertEquals("openai-compatible", cloud.providerType());
        assertTrue(cloud.enabled());
        assertTrue(cloud.baseUrlConfigured());
        assertTrue(cloud.apiKeyConfigured());
        assertEquals("已配置", cloud.apiKeyMask());
        assertEquals("已配置", cloud.modelDisplay());

        String serialized = cloud.toString().toLowerCase();
        assertFalse(serialized.contains("demo-key"));
        assertFalse(serialized.contains("https://"));
        assertFalse(serialized.contains("authorization"));
        assertFalse(serialized.contains("api_key=sk"));
    }

    @Test
    void unknownProviderTestReturnsBadRequestSemantics() {
        var service = new AiAgentProviderConfigService(new StubPythonClient(new ObjectMapper()));

        var ex = assertThrows(ResponseStatusException.class, () -> service.testProvider("missing-provider"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("invalid_ai_provider_key", ex.getReason());
    }

    @Test
    void pythonUnavailableProviderTestReturnsFailedSanitizedMessage() {
        var service = new AiAgentProviderConfigService(new UnavailablePythonClient(new ObjectMapper()));

        var result = service.testProvider("cloud-openai-compatible");

        assertEquals("cloud-openai-compatible", result.providerKey());
        assertEquals("企业云模型", result.displayName());
        assertEquals("failed", result.status());
        assertFalse(result.message().toLowerCase().contains("sk-"));
        assertFalse(result.message().toLowerCase().contains("authorization"));
        assertFalse(result.message().toLowerCase().contains("http://"));
    }

    private static class StubPythonClient extends PythonAiAgentClient {
        StubPythonClient(ObjectMapper mapper) {
            super(mapper, new AiAgentProperties("http://127.0.0.1:1", 100, true));
        }

        @Override
        public List<Map<String, Object>> providers() {
            return List.of(
                Map.of(
                    "key", "cloud-openai-compatible",
                    "type", "cloud",
                    "enabled", true,
                    "baseUrlConfigured", true,
                    "apiKeyConfigured", true,
                    "modelConfigured", true,
                "api_key", "demo-key",
                    "base_url", "https://model.example/v1/chat/completions"
                ),
                Map.of(
                    "key", "fallback-template",
                    "type", "fallback",
                    "enabled", true,
                    "baseUrlConfigured", false,
                    "apiKeyConfigured", false,
                    "modelConfigured", true
                )
            );
        }

        @Override
        public ProviderTestResult testProvider(String providerKey) {
            return new ProviderTestResult(providerKey, providerKey, "passed", "ok", Instant.EPOCH.toString());
        }
    }

    private static class UnavailablePythonClient extends StubPythonClient {
        UnavailablePythonClient(ObjectMapper mapper) {
            super(mapper);
        }

        @Override
        public ProviderTestResult testProvider(String providerKey) {
            return new ProviderTestResult(
                providerKey,
                providerKey,
                "failed",
                "Authorization Bearer demo-key failed at http://model.example",
                Instant.EPOCH.toString()
            );
        }
    }
}
