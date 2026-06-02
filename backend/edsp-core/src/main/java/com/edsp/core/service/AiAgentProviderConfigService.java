package com.edsp.core.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiAgentProviderConfigService {
    private static final Set<String> TESTABLE_PROVIDER_KEYS = Set.of(
        "local-openai-compatible",
        "cloud-openai-compatible",
        "fallback-template"
    );
    private static final Pattern UNSAFE_MESSAGE_PATTERN = Pattern.compile(
        "(?i)(https?://\\S+|authorization\\b\\S*|bearer\\s+\\S+|sk-[a-z0-9._-]+|"
            + "api[_-]?key\\s*[:=]?\\s*\\S+|token\\s*[:=]\\s*\\S+|secret\\s*[:=]\\s*\\S+|password\\s*[:=]\\s*\\S+)"
    );

    private final PythonAiAgentClient pythonClient;

    public AiAgentProviderConfigService(PythonAiAgentClient pythonClient) {
        this.pythonClient = pythonClient;
    }

    public List<ProviderConfigRow> listConfigs() {
        return pythonClient.providers().stream()
            .map(this::toConfigRow)
            .toList();
    }

    public PythonAiAgentClient.ProviderTestResult testProvider(String providerKey) {
        if (!TESTABLE_PROVIDER_KEYS.contains(providerKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_ai_provider_key");
        }
        var result = pythonClient.testProvider(providerKey);
        return new PythonAiAgentClient.ProviderTestResult(
            providerKey,
            displayName(providerKey),
            normalizeStatus(result.status()),
            sanitizeMessage(result.message()),
            result.testedAt()
        );
    }

    private ProviderConfigRow toConfigRow(Map<String, Object> provider) {
        var key = string(provider.get("key"));
        var enabled = flag(provider.get("enabled"));
        var baseUrlConfigured = flag(provider.get("baseUrlConfigured"));
        var apiKeyConfigured = flag(provider.get("apiKeyConfigured"));
        var modelConfigured = flag(provider.get("modelConfigured"));
        var fallback = "fallback-template".equals(key);
        return new ProviderConfigRow(
            key,
            displayName(key),
            providerType(key),
            enabled,
            baseUrlConfigured,
            apiKeyConfigured,
            fallback ? "不需要" : configuredLabel(apiKeyConfigured),
            modelConfigured,
            fallback ? "内置模板" : configuredLabel(modelConfigured),
            fallback ? "passed" : "unknown",
            fallback ? "安全模板可用" : (enabled ? "尚未测试" : "请先完成模型接口配置"),
            false,
            configureHint(key),
            TESTABLE_PROVIDER_KEYS.contains(key)
        );
    }

    private String displayName(String key) {
        return switch (key) {
            case "local-openai-compatible" -> "本地模型";
            case "local-ollama-compatible" -> "本地 Ollama 模型";
            case "cloud-openai-compatible" -> "企业云模型";
            case "fallback-template" -> "安全模板生成";
            default -> "其他分析模型";
        };
    }

    private String providerType(String key) {
        return switch (key) {
            case "fallback-template" -> "fallback";
            case "local-ollama-compatible" -> "ollama-compatible";
            default -> "openai-compatible";
        };
    }

    private String configureHint(String key) {
        return switch (key) {
            case "cloud-openai-compatible" ->
                "请在部署环境中配置企业云模型的接口地址、API Key 和模型名称。";
            case "local-openai-compatible" ->
                "请通过部署环境变量配置本地 OpenAI-compatible 模型服务。";
            case "local-ollama-compatible" ->
                "请通过部署环境变量配置本地 Ollama 模型服务。";
            case "fallback-template" ->
                "模型不可用时可使用内置安全模板生成只读建议。";
            default -> "请联系管理员检查模型接入配置。";
        };
    }

    private String configuredLabel(boolean configured) {
        return configured ? "已配置" : "未配置";
    }

    private String normalizeStatus(String status) {
        return "passed".equals(status) ? "passed" : "failed";
    }

    private String sanitizeMessage(String message) {
        var text = message == null || message.isBlank()
            ? "模型连接测试失败，请检查配置。"
            : message;
        var sanitized = UNSAFE_MESSAGE_PATTERN.matcher(text).replaceAll("[redacted]");
        return sanitized.isBlank() ? "模型连接测试失败，请检查配置。" : sanitized;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean flag(Object value) {
        return Boolean.TRUE.equals(value);
    }

    public record ProviderConfigRow(
        String providerKey,
        String displayName,
        String providerType,
        boolean enabled,
        boolean baseUrlConfigured,
        boolean apiKeyConfigured,
        String apiKeyMask,
        boolean modelConfigured,
        String modelDisplay,
        String lastTestStatus,
        String lastTestMessage,
        boolean editableInUi,
        String configureHint,
        boolean testable
    ) {}
}
