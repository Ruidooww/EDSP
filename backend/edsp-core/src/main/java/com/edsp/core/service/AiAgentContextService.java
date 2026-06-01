package com.edsp.core.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiAgentContextService {
    private final JdbcTemplate jdbcTemplate;

    public AiAgentContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Long> aggregate(String period) {
        var cutoff = Timestamp.from(Instant.now().minus(periodDays(period), ChronoUnit.DAYS));
        var context = new LinkedHashMap<String, Long>();
        context.put("rawEventCount", count("select count(*) from raw_events where received_at >= ?", cutoff));
        context.put("standardEventCount", count("select count(*) from standard_events where created_at >= ?", cutoff));
        context.put("alertDecisionCount", count("select count(*) from alert_decisions where created_at >= ?", cutoff));
        context.put("matchedDecisionCount", count("select count(*) from alert_decisions where lower(decision) = 'matched' and created_at >= ?", cutoff));
        context.put("notMatchedDecisionCount", count("select count(*) from alert_decisions where lower(decision) = 'not_matched' and created_at >= ?", cutoff));
        context.put("errorDecisionCount", count("select count(*) from alert_decisions where lower(decision) = 'error' and created_at >= ?", cutoff));
        context.put("alertCount", count("select count(*) from alerts where created_at >= ?", cutoff));
        context.put("openAlertCount", count("select count(*) from alerts where lower(status) = 'open'"));
        context.put("criticalAlertCount", count("select count(*) from alerts where lower(severity) = 'critical'"));
        context.put("highAlertCount", count("select count(*) from alerts where lower(severity) = 'high'"));
        context.put("warningSyncCount", count("select count(*) from ingestion_plan_sync_runs where lower(status) = 'warning' and started_at >= ?", cutoff));
        context.put("failedDecisionCount", count("select count(*) from alert_decisions where lower(decision) = 'error' and created_at >= ?", cutoff));
        context.put("notificationDeliveryCount", count("select count(*) from notification_deliveries where created_at >= ?", cutoff));
        return Map.copyOf(context);
    }

    private int periodDays(String period) {
        return switch (period) {
            case "last_24h" -> 1;
            case "last_30_days" -> 30;
            default -> 7;
        };
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
