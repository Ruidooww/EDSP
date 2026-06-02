package com.edsp.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiAgentRunService {
    private static final Set<String> PERIODS = Set.of("last_24h", "last_7_days", "last_30_days");
    private static final Set<String> THEMES = Set.of(
        "security_overview", "high_risk_alerts", "rule_effectiveness", "sync_pipeline_health", "notification_readiness"
    );
    private static final Set<String> RESPONSE_SOURCES = Set.of("llm", "fallback-template", "java-fallback");
    private static final Set<String> RESPONSE_STATUSES = Set.of("passed", "warning");
    private static final Pattern SAFE_CODE_PATTERN = Pattern.compile("[a-z0-9_-]{1,100}");
    private static final Pattern UNSAFE_SUMMARY_PATTERN = Pattern.compile(
        "(?i)(https?://|jdbc:|bearer\\s+\\S+|authorization|api\\s*[_-]?\\s*key|token|secret|password|"
            + "payload_json|normalized_json|extra_json|config_json|raw_events|source[_ -]?config|"
            + "\\bsql\\b|\\bselect\\b.+\\bfrom\\b|\\binsert\\s+into\\b|\\bupdate\\s+\\w+\\s+set\\b|"
            + "\\bdelete\\s+from\\b|\\bshell\\b|/etc/passwd|\\bsend\\s+(?:a\\s+)?notification\\b|"
            + "\\bclose\\s+(?:all\\s+)?(?:critical\\s+)?alerts?\\b|已执行|已关闭告警|已发送通知|已修改规则)"
    );
    private static final String RESPONSE_GUARD_CODE = "ai_agent_response_guard_fallback";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiAgentContextService contextService;
    private final PythonAiAgentClient pythonClient;

    public AiAgentRunService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AiAgentContextService contextService,
        PythonAiAgentClient pythonClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.contextService = contextService;
        this.pythonClient = pythonClient;
    }

    public PythonAiAgentClient.AgentResponse run(RunRequest request) {
        validate(request);
        var startedAt = Timestamp.from(Instant.now());
        var context = contextService.aggregate(request.period());
        var guardedResponse = guardResponse(request, pythonClient.run(new PythonAiAgentClient.AgentRequest(
            request.agentKey(), request.providerKey(), request.period(), request.theme(), context
        )));
        var response = guardedResponse.response();
        jdbcTemplate.update("""
            insert into ai_agent_runs(
                agent_key, provider_key, theme, period, status, source,
                input_summary_json, output_summary_json, warning_summary_json, error_code, started_at, finished_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            request.agentKey(), request.providerKey(), request.theme(), request.period(),
            response.status(), response.source(), json(context),
            json(Map.of(
                "sectionCount", response.sections().size(),
                "titles", response.sections().stream().map(PythonAiAgentClient.Section::title).toList()
            )),
            json(response.warnings()), guardedResponse.errorCode(), startedAt, Timestamp.from(Instant.now())
        );
        return response;
    }

    public List<Map<String, Object>> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return jdbcTemplate.queryForList("""
            select id, agent_key, provider_key, theme, period, status, source, started_at, finished_at
            from ai_agent_runs
            order by started_at desc, id desc
            limit ?
            """, safeLimit);
    }

    private void validate(RunRequest request) {
        if (!"security-insight-agent".equals(request.agentKey())
            || !PERIODS.contains(request.period())
            || !THEMES.contains(request.theme())) {
            throw new IllegalArgumentException("invalid_ai_agent_request");
        }
    }

    private GuardedResponse guardResponse(RunRequest request, PythonAiAgentClient.AgentResponse response) {
        if (response == null
            || !Objects.equals(request.agentKey(), response.agentKey())
            || !Objects.equals(request.providerKey(), response.providerKey())
            || !Objects.equals(request.period(), response.period())
            || !Objects.equals(request.theme(), response.theme())
            || !RESPONSE_SOURCES.contains(response.source())
            || !RESPONSE_STATUSES.contains(response.status())
            || response.sections() == null
            || response.sections().isEmpty()
            || response.sections().size() > 5
            || response.sections().stream().anyMatch(section ->
                !safeText(section.title(), 40) || !safeText(section.content(), 500)
            )
            || response.warnings() == null
            || response.warnings().stream().anyMatch(warning ->
                warning == null || !SAFE_CODE_PATTERN.matcher(warning).matches()
            )) {
            return fallback(request);
        }
        return new GuardedResponse(response, null);
    }

    private boolean safeText(String value, int maxLength) {
        return value != null
            && !value.isBlank()
            && value.length() <= maxLength
            && !UNSAFE_SUMMARY_PATTERN.matcher(value).find();
    }

    private GuardedResponse fallback(RunRequest request) {
        return new GuardedResponse(new PythonAiAgentClient.AgentResponse(
            request.agentKey(), request.providerKey(), request.period(), request.theme(),
            "java-fallback", "warning",
            List.of(
                new PythonAiAgentClient.Section("安全态势概览", "AI 服务当前不可用，已返回安全聚合摘要。"),
                new PythonAiAgentClient.Section("开放告警", "请优先人工检查开放告警和高危告警。"),
                new PythonAiAgentClient.Section("规则决策", "请检查失败决策与规则命中情况。"),
                new PythonAiAgentClient.Section("同步链路", "请检查 warning 同步运行。"),
                new PythonAiAgentClient.Section("建议动作", "保持人工复核，不会自动发送通知或修改告警状态。")
            ),
            List.of(RESPONSE_GUARD_CODE)
        ), RESPONSE_GUARD_CODE);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    public record RunRequest(String agentKey, String providerKey, String period, String theme) {}

    private record GuardedResponse(PythonAiAgentClient.AgentResponse response, String errorCode) {}
}

