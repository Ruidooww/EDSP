package com.edsp.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiAgentRunService {
    private static final Set<String> PERIODS = Set.of("last_24h", "last_7_days", "last_30_days");
    private static final Set<String> THEMES = Set.of(
        "security_overview", "high_risk_alerts", "rule_effectiveness", "sync_pipeline_health", "notification_readiness"
    );

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
        var response = pythonClient.run(new PythonAiAgentClient.AgentRequest(
            request.agentKey(), request.providerKey(), request.period(), request.theme(), context
        ));
        jdbcTemplate.update("""
            insert into ai_agent_runs(
                agent_key, provider_key, theme, period, status, source,
                input_summary_json, output_summary_json, warning_summary_json, started_at, finished_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            request.agentKey(), request.providerKey(), request.theme(), request.period(),
            response.status(), response.source(), json(context),
            json(Map.of("sectionCount", response.sections().size())),
            json(response.warnings()), startedAt, Timestamp.from(Instant.now())
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    public record RunRequest(String agentKey, String providerKey, String period, String theme) {}
}

