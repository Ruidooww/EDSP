package com.edsp.core.service;

import com.edsp.core.config.AiAgentProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PythonAiAgentClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern UNSAFE_OUTPUT_PATTERN = Pattern.compile(
        "(?i)(https?://|select\\s+.+\\s+from|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from|"
            + "token\\s*[:=]|secret\\s*[:=]|password\\s*[:=]|api[_-]?key\\s*[:=]|authorization\\s*[:=]|"
            + "已执行|已关闭告警|已发送通知)"
    );

    private final ObjectMapper objectMapper;
    private final AiAgentProperties properties;
    private final HttpClient httpClient;

    public PythonAiAgentClient(ObjectMapper objectMapper, AiAgentProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
            .build();
    }

    public List<Map<String, Object>> providers() {
        try {
            var request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/agent/providers"))
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return fallbackProviders();
            }
            var payload = objectMapper.readValue(response.body(), MAP_TYPE);
            Object providers = payload.get("providers");
            return providers instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> safeProvider((Map<?, ?>) item)).toList()
                : fallbackProviders();
        } catch (Exception ex) {
            return fallbackProviders();
        }
    }

    private Map<String, Object> safeProvider(Map<?, ?> provider) {
        return Map.of(
            "key", string(provider.get("key")),
            "type", string(provider.get("type")),
            "enabled", flag(provider.get("enabled")),
            "baseUrlConfigured", flag(provider.get("baseUrlConfigured")),
            "apiKeyConfigured", flag(provider.get("apiKeyConfigured")),
            "modelConfigured", flag(provider.get("modelConfigured"))
        );
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean flag(Object value) {
        return Boolean.TRUE.equals(value);
    }

    public AgentResponse run(AgentRequest request) {
        try {
            var httpRequest = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/agent/runs"))
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return fallback(request);
            }
            var result = objectMapper.readValue(response.body(), AgentResponse.class);
            return safe(result) ? result : fallback(request);
        } catch (Exception ex) {
            return fallback(request);
        }
    }

    private boolean safe(AgentResponse response) {
        return response != null && response.sections() != null && !response.sections().isEmpty()
            && response.sections().stream().allMatch(section ->
                section.title() != null && section.title().length() <= 40
                    && section.content() != null && section.content().length() <= 500
                    && !UNSAFE_OUTPUT_PATTERN.matcher(section.title() + " " + section.content()).find()
            );
    }

    private AgentResponse fallback(AgentRequest request) {
        return new AgentResponse(
            request.agentKey(), request.providerKey(), request.period(), request.theme(),
            "java-fallback", "warning",
            List.of(
                new Section("安全态势概览", "AI 服务当前不可用，已返回安全聚合摘要。"),
                new Section("开放告警", "请优先人工检查开放告警和高危告警。"),
                new Section("规则决策", "请检查失败决策与规则命中情况。"),
                new Section("同步链路", "请检查 warning 同步运行。"),
                new Section("建议动作", "保持人工复核，不会自动发送通知或修改告警状态。")
            ),
            List.of("python_agent_fallback_used")
        );
    }

    private List<Map<String, Object>> fallbackProviders() {
        return List.of(Map.of(
            "key", "fallback-template",
            "type", "fallback",
            "enabled", true,
            "baseUrlConfigured", false,
            "apiKeyConfigured", false,
            "modelConfigured", true
        ));
    }

    public record AgentRequest(
        String agentKey,
        String providerKey,
        String period,
        String theme,
        Map<String, Long> context
    ) {}

    public record Section(String title, String content) {}

    public record AgentResponse(
        String agentKey,
        String providerKey,
        String period,
        String theme,
        String source,
        String status,
        List<Section> sections,
        List<String> warnings
    ) {}
}
