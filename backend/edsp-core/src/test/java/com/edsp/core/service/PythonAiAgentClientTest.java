package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.core.config.AiAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PythonAiAgentClientTest {
    @Test
    void returnsJavaFallbackWhenPythonIsUnavailable() {
        var client = new PythonAiAgentClient(new ObjectMapper(), new AiAgentProperties("http://127.0.0.1:1", 100, true));

        var result = client.run(new PythonAiAgentClient.AgentRequest(
            "security-insight-agent", "fallback-template", "last_7_days", "security_overview", Map.of()
        ));

        assertEquals("java-fallback", result.source());
        assertEquals("warning", result.status());
        assertEquals(5, result.sections().size());
    }

    @Test
    void acceptsSafePythonResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent/runs", exchange -> {
            var body = """
                {"agentKey":"security-insight-agent","providerKey":"fallback-template","period":"last_7_days",
                "theme":"security_overview","source":"fallback-template","status":"warning",
                "sections":[{"title":"安全态势概览","content":"仅包含安全聚合信息"}],"warnings":[]}
                """;
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var client = new PythonAiAgentClient(
                new ObjectMapper(), new AiAgentProperties("http://127.0.0.1:" + server.getAddress().getPort(), 1000, true)
            );
            var result = client.run(new PythonAiAgentClient.AgentRequest(
                "security-insight-agent", "fallback-template", "last_7_days", "security_overview", Map.of()
            ));
            assertEquals("fallback-template", result.source());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsafePythonResponseAndFallsBack() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent/runs", exchange -> {
            var body = """
                {"agentKey":"security-insight-agent","providerKey":"fallback-template","period":"last_7_days",
                "theme":"security_overview","source":"llm","status":"passed",
                "sections":[{"title":"unsafe","content":"select * from alerts"}],"warnings":[]}
                """;
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var client = new PythonAiAgentClient(
                new ObjectMapper(), new AiAgentProperties("http://127.0.0.1:" + server.getAddress().getPort(), 1000, true)
            );
            var result = client.run(new PythonAiAgentClient.AgentRequest(
                "security-insight-agent", "fallback-template", "last_7_days", "security_overview", Map.of()
            ));
            assertEquals("java-fallback", result.source());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void filtersUnsafeProviderDiscoveryFields() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent/providers", exchange -> {
            var body = """
                {"providers":[{"key":"unsafe","type":"cloud","enabled":true,
                "baseUrlConfigured":true,"apiKeyConfigured":true,"modelConfigured":true,
                "base_url":"https://example.test","api_key":"do-not-expose"}]}
                """;
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var client = new PythonAiAgentClient(
                new ObjectMapper(), new AiAgentProperties("http://127.0.0.1:" + server.getAddress().getPort(), 1000, true)
            );
            var provider = client.providers().getFirst();
            assertEquals(false, provider.containsKey("base_url"));
            assertEquals(false, provider.containsKey("api_key"));
        } finally {
            server.stop(0);
        }
    }
}
