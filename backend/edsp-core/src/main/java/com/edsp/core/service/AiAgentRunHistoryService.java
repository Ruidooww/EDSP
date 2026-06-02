package com.edsp.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiAgentRunHistoryService {
    private static final Set<String> SAFE_METRIC_KEYS = Set.of(
        "rawEventCount",
        "standardEventCount",
        "alertDecisionCount",
        "matchedDecisionCount",
        "notMatchedDecisionCount",
        "errorDecisionCount",
        "alertCount",
        "openAlertCount",
        "criticalAlertCount",
        "highAlertCount",
        "warningSyncCount",
        "failedDecisionCount",
        "notificationDeliveryCount"
    );
    private static final Pattern SAFE_CODE_PATTERN = Pattern.compile("[a-z0-9_-]{1,100}");
    private static final Pattern UNSAFE_TEXT_PATTERN = Pattern.compile(
        "(?i)(https?://|jdbc:|authorization|bearer|api\\s*[_-]?\\s*key|token|secret|password|endpoint|"
            + "payload_json|normalized_json|extra_json|config_json)"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AiAgentRunHistoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<HistoryRow> list(HistoryFilter filter) {
        var sql = new StringBuilder("""
            select id, agent_key, provider_key, theme, period, status, source,
                   output_summary_json, warning_summary_json, started_at, finished_at
            from ai_agent_runs
            where 1 = 1
            """);
        var args = new ArrayList<Object>();
        appendFilter(sql, args, "status", filter.status());
        appendFilter(sql, args, "source", filter.source());
        appendFilter(sql, args, "provider_key", filter.providerKey());
        appendFilter(sql, args, "theme", filter.theme());
        appendFilter(sql, args, "period", filter.period());
        sql.append(" order by started_at desc, id desc limit ?");
        args.add(Math.max(1, Math.min(filter.limit(), 100)));
        return jdbcTemplate.query(sql.toString(), this::toHistoryRow, args.toArray());
    }

    public HistoryDetail detail(long id) {
        var rows = jdbcTemplate.query("""
            select id, agent_key, provider_key, theme, period, model_name, status, source,
                   input_summary_json, output_summary_json, warning_summary_json,
                   error_code, started_at, finished_at
            from ai_agent_runs
            where id = ?
            """, this::toHistoryDetail, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ai_agent_run_not_found");
        }
        return rows.getFirst();
    }

    private void appendFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" and ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private HistoryRow toHistoryRow(ResultSet resultSet, int rowNum) throws SQLException {
        var startedAt = instant(resultSet, "started_at");
        var finishedAt = instant(resultSet, "finished_at");
        return new HistoryRow(
            resultSet.getLong("id"),
            resultSet.getString("agent_key"),
            resultSet.getString("provider_key"),
            resultSet.getString("theme"),
            resultSet.getString("period"),
            resultSet.getString("status"),
            resultSet.getString("source"),
            sectionCount(resultSet.getString("output_summary_json")),
            warnings(resultSet.getString("warning_summary_json")).size(),
            startedAt,
            finishedAt,
            durationMs(startedAt, finishedAt)
        );
    }

    private HistoryDetail toHistoryDetail(ResultSet resultSet, int rowNum) throws SQLException {
        var startedAt = instant(resultSet, "started_at");
        var finishedAt = instant(resultSet, "finished_at");
        return new HistoryDetail(
            resultSet.getLong("id"),
            resultSet.getString("agent_key"),
            resultSet.getString("provider_key"),
            resultSet.getString("theme"),
            resultSet.getString("period"),
            resultSet.getString("status"),
            resultSet.getString("source"),
            safeText(resultSet.getString("model_name")),
            new InputSummary(metricKeys(resultSet.getString("input_summary_json")), true),
            new OutputSummary(
                sectionCount(resultSet.getString("output_summary_json")),
                titles(resultSet.getString("output_summary_json"))
            ),
            warnings(resultSet.getString("warning_summary_json")),
            safeCode(resultSet.getString("error_code")),
            startedAt,
            finishedAt,
            durationMs(startedAt, finishedAt)
        );
    }

    private List<String> metricKeys(String json) {
        var keys = new ArrayList<String>();
        var node = readJson(json);
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(key -> {
                if (SAFE_METRIC_KEYS.contains(key)) {
                    keys.add(key);
                }
            });
        }
        return List.copyOf(keys);
    }

    private int sectionCount(String json) {
        return Math.max(0, readJson(json).path("sectionCount").asInt(0));
    }

    private List<String> titles(String json) {
        var result = new ArrayList<String>();
        var titles = readJson(json).path("titles");
        if (titles.isArray()) {
            titles.forEach(node -> {
                var title = safeText(node.asText());
                if (title != null && !title.isBlank()) {
                    result.add(title);
                }
            });
        }
        return List.copyOf(result);
    }

    private List<String> warnings(String json) {
        var result = new LinkedHashSet<String>();
        var warnings = readJson(json);
        if (warnings.isArray()) {
            warnings.forEach(node -> {
                var warning = safeCode(node.asText());
                result.add(warning == null ? "ai_agent_warning_redacted" : warning);
            });
        }
        return List.copyOf(result);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String safeText(String value) {
        if (value == null || value.isBlank() || value.length() > 160 || UNSAFE_TEXT_PATTERN.matcher(value).find()) {
            return null;
        }
        return value;
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank() || !SAFE_CODE_PATTERN.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long durationMs(Instant startedAt, Instant finishedAt) {
        return startedAt == null || finishedAt == null ? null : ChronoUnit.MILLIS.between(startedAt, finishedAt);
    }

    public record HistoryFilter(
        int limit,
        String status,
        String source,
        String providerKey,
        String theme,
        String period
    ) {}

    public record HistoryRow(
        long id,
        String agentKey,
        String providerKey,
        String theme,
        String period,
        String status,
        String source,
        int sectionCount,
        int warningCount,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs
    ) {}

    public record InputSummary(List<String> metricKeys, boolean sensitiveFieldsExcluded) {}

    public record OutputSummary(int sectionCount, List<String> titles) {}

    public record HistoryDetail(
        long id,
        String agentKey,
        String providerKey,
        String theme,
        String period,
        String status,
        String source,
        String modelName,
        InputSummary inputSummary,
        OutputSummary outputSummary,
        List<String> warnings,
        String errorCode,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs
    ) {}
}
