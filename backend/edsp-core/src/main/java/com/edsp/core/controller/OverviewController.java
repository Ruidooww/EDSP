package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core")
public class OverviewController {
    private static final int MIN_PATH_SECRET_LENGTH = 16;
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9._~+/=-]{8,})");
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)\\b(key|token|access_token|accessToken|secret|sign|signature|api_key|apikey|password|passwd|auth|authorization|bearer)\\s*[:=]\\s*([^\\s&\"'{}<>]+)"
    );

    private final JdbcTemplate jdbcTemplate;

    public OverviewController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        var data = new LinkedHashMap<String, Object>();
        data.put("requestTime", OffsetDateTime.now().toString());
        data.put("dataSources", dataSourceSummary());
        data.put("schema", schemaSummary());
        data.put("rules", ruleSummary());
        data.put("alerts", alertSummary());
        data.put("securityOperations", securityOperationsSummary());
        data.put("notificationDelivery", notificationDeliverySummary());
        data.put("notificationChannels", notificationChannelSummary());
        data.put("reports", reportSummary());
        data.put("recentDataSources", recentDataSources());
        data.put("recentLifecycleEvents", recentLifecycleEvents());
        return ApiResponse.ok(data);
    }

    private Map<String, Object> dataSourceSummary() {
        long total = count("select count(*) from data_sources");
        long healthy = count("""
            select count(*) from data_sources
            where lower(status) in ('active', 'healthy', 'connected', 'ok', 'ready', 'configured')
            """);
        long abnormal = count("""
            select count(*) from data_sources
            where lower(status) in ('error', 'failed', 'offline', 'abnormal')
            """);
        long enabled = count("select count(*) from data_sources where enabled = true");
        long unchecked = Math.max(0, total - healthy - abnormal);

        var summary = new LinkedHashMap<String, Object>();
        summary.put("total", total);
        summary.put("healthy", healthy);
        summary.put("abnormal", abnormal);
        summary.put("unchecked", unchecked);
        summary.put("enabled", enabled);
        summary.put("disabled", Math.max(0, total - enabled));
        summary.put("healthRate", rate(healthy, total));
        return summary;
    }

    private Map<String, Object> schemaSummary() {
        long tables = count("select count(*) from schema_tables");
        long fields = count("select count(*) from schema_fields");
        long mappings = count("select count(*) from field_mappings");
        long confirmedTables = count("""
            select count(*) from schema_tables
            where lower(confirmation_status) in ('confirmed', 'approved', 'done')
            """);

        var summary = new LinkedHashMap<String, Object>();
        summary.put("tables", tables);
        summary.put("fields", fields);
        summary.put("mappings", mappings);
        summary.put("confirmedTables", confirmedTables);
        summary.put("mappedRate", rate(mappings, fields));
        return summary;
    }

    private Map<String, Object> ruleSummary() {
        long total = count("select count(*) from rules");
        long enabled = count("select count(*) from rules where enabled = true");

        var summary = new LinkedHashMap<String, Object>();
        summary.put("total", total);
        summary.put("enabled", enabled);
        summary.put("disabled", Math.max(0, total - enabled));
        summary.put("enabledRate", rate(enabled, total));
        return summary;
    }

    private Map<String, Object> alertSummary() {
        var today = LocalDate.now(ZoneId.systemDefault()).atStartOfDay();
        var yesterday = today.minusDays(1);
        var tomorrow = today.plusDays(1);

        long open = count("""
            select count(*) from alerts
            where lower(status) not in ('closed', 'resolved', 'done', 'archived')
            """);
        long todayCount = count("select count(*) from alerts where created_at >= ? and created_at < ?",
            Timestamp.valueOf(today), Timestamp.valueOf(tomorrow));
        long yesterdayCount = count("select count(*) from alerts where created_at >= ? and created_at < ?",
            Timestamp.valueOf(yesterday), Timestamp.valueOf(today));

        var summary = new LinkedHashMap<String, Object>();
        summary.put("open", open);
        summary.put("today", todayCount);
        summary.put("yesterday", yesterdayCount);
        summary.put("delta", todayCount - yesterdayCount);
        summary.put("bySeverity", countBy("""
            select lower(severity) as bucket, count(*) as total
            from alerts
            group by lower(severity)
            """));
        summary.put("byStatus", countBy("""
            select lower(status) as bucket, count(*) as total
            from alerts
            group by lower(status)
            """));
        summary.put("trend", alertTrend());
        summary.put("recent", recentAlerts());
        return summary;
    }

    private Map<String, Object> securityOperationsSummary() {
        var today = LocalDate.now(ZoneId.systemDefault()).atStartOfDay();
        var tomorrow = today.plusDays(1);

        long total = count("select count(*) from alerts");
        long open = count("select count(*) from alerts where lower(status) = 'open'");
        long acknowledged = count("select count(*) from alerts where lower(status) = 'acknowledged'");
        long closed = count("select count(*) from alerts where lower(status) = 'closed'");
        long highRisk = count("select count(*) from alerts where lower(severity) in ('critical', 'high')");
        long todayCount = count("select count(*) from alerts where created_at >= ? and created_at < ?",
            Timestamp.valueOf(today), Timestamp.valueOf(tomorrow));

        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalAlerts", total);
        summary.put("openAlerts", open);
        summary.put("acknowledgedAlerts", acknowledged);
        summary.put("closedAlerts", closed);
        summary.put("highRiskAlerts", highRisk);
        summary.put("todayAlerts", todayCount);
        return summary;
    }

    private Map<String, Object> notificationDeliverySummary() {
        var today = LocalDate.now(ZoneId.systemDefault()).atStartOfDay();
        var tomorrow = today.plusDays(1);

        long todayTotal = count("""
            select count(*) from notification_deliveries
            where created_at >= ? and created_at < ?
            """, Timestamp.valueOf(today), Timestamp.valueOf(tomorrow));
        long todaySuccess = count("""
            select count(*) from notification_deliveries
            where lower(status) = 'success' and created_at >= ? and created_at < ?
            """, Timestamp.valueOf(today), Timestamp.valueOf(tomorrow));
        long todayFailed = count("""
            select count(*) from notification_deliveries
            where lower(status) = 'failed' and created_at >= ? and created_at < ?
            """, Timestamp.valueOf(today), Timestamp.valueOf(tomorrow));

        var summary = new LinkedHashMap<String, Object>();
        summary.put("todayTotal", todayTotal);
        summary.put("todaySuccess", todaySuccess);
        summary.put("todayFailed", todayFailed);
        summary.put("todaySuccessRate", rate(todaySuccess, todayTotal));
        summary.put("retryableFailed", count("""
            select count(*) from notification_deliveries
            where lower(status) = 'failed' and retryable = true
            """));
        summary.put("byFailureType", countBy("""
            select lower(failure_type) as bucket, count(*) as total
            from notification_deliveries
            where lower(status) = 'failed' and failure_type is not null
            group by lower(failure_type)
            order by total desc, bucket
            limit 5
            """));
        summary.put("recentFailed", recentFailedDeliveries());
        return summary;
    }

    private Map<String, Object> notificationChannelSummary() {
        long total = count("select count(*) from notification_channels");
        long enabled = count("select count(*) from notification_channels where enabled = true");

        var summary = new LinkedHashMap<String, Object>();
        summary.put("total", total);
        summary.put("enabled", enabled);
        summary.put("disabled", Math.max(0, total - enabled));
        summary.put("byType", countBy("""
            select lower(channel_type) as bucket, count(*) as total
            from notification_channels
            group by lower(channel_type)
            order by total desc, bucket
            """));
        return summary;
    }

    private Map<String, Object> reportSummary() {
        long total = count("select count(*) from report_jobs");

        var summary = new LinkedHashMap<String, Object>();
        summary.put("total", total);
        summary.put("completed", count("""
            select count(*) from report_jobs
            where lower(status) in ('completed', 'success', 'done')
            """));
        summary.put("running", count("""
            select count(*) from report_jobs
            where lower(status) in ('running', 'processing')
            """));
        summary.put("failed", count("""
            select count(*) from report_jobs
            where lower(status) in ('failed', 'error')
            """));
        summary.put("pending", count("""
            select count(*) from report_jobs
            where lower(status) in ('pending', 'created')
            """));
        summary.put("byStatus", countBy("""
            select lower(status) as bucket, count(*) as total
            from report_jobs
            group by lower(status)
            """));
        return summary;
    }

    private List<Map<String, Object>> recentDataSources() {
        var rows = jdbcTemplate.queryForList("""
            select ds.id, ds.name, ds.source_type, ds.connection_kind, ds.status, ds.enabled, ds.updated_at,
                   count(distinct sf.id) as field_count,
                   count(distinct fm.id) as mapping_count
            from data_sources ds
            left join schema_tables st on st.data_source_id = ds.id
            left join schema_fields sf on sf.schema_table_id = st.id
            left join field_mappings fm on fm.schema_table_id = st.id and fm.source_field = sf.field_name
            group by ds.id, ds.name, ds.source_type, ds.connection_kind, ds.status, ds.enabled, ds.updated_at
            order by ds.updated_at desc
            limit 6
            """);

        for (var row : rows) {
            long fields = asLong(row.get("field_count"));
            long mappings = asLong(row.get("mapping_count"));
            row.put("mapped_percent", rate(mappings, fields));
        }
        return rows;
    }

    private List<Map<String, Object>> recentAlerts() {
        return jdbcTemplate.queryForList("""
            select id, title, severity, status, subject_type, subject_ref, created_at
            from alerts
            order by created_at desc
            limit 5
            """);
    }

    private List<Map<String, Object>> recentFailedDeliveries() {
        return jdbcTemplate.queryForList("""
            select d.id, d.channel_id, c.name as channel_name, c.channel_type,
                   d.alert_id, a.title as alert_title, d.title, d.status,
                   d.response_code, d.failure_type, d.failure_reason,
                   coalesce(d.retryable, false) as retryable,
                   coalesce(d.retry_count, 0) as retry_count,
                   d.retry_of_delivery_id, d.created_at, c.endpoint_url
            from notification_deliveries d
            left join notification_channels c on c.id = d.channel_id
            left join alerts a on a.id = d.alert_id
            where lower(d.status) = 'failed'
            order by d.created_at desc, d.id desc
            limit 5
            """).stream().map(this::presentFailedDelivery).toList();
    }

    private Map<String, Object> presentFailedDelivery(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        var endpointUrl = stringOrBlank(row.get("endpoint_url"));
        result.put("failure_reason", redactText(row.get("failure_reason"), endpointUrl));
        result.remove("endpoint_url");
        return result;
    }

    private List<Map<String, Object>> recentLifecycleEvents() {
        return jdbcTemplate.queryForList("""
            select e.id, e.alert_id, a.title as alert_title, e.event_type,
                   e.operator_name, e.assignee, e.created_at
            from alert_lifecycle_events e
            left join alerts a on a.id = e.alert_id
            order by e.created_at desc, e.id desc
            limit 6
            """);
    }

    private List<Map<String, Object>> alertTrend() {
        var points = new ArrayList<Map<String, Object>>();
        var start = LocalDate.now(ZoneId.systemDefault()).minusDays(6);

        for (int i = 0; i < 7; i++) {
            var day = start.plusDays(i);
            long value = count("select count(*) from alerts where created_at >= ? and created_at < ?",
                Timestamp.valueOf(day.atStartOfDay()), Timestamp.valueOf(day.plusDays(1).atStartOfDay()));

            var point = new LinkedHashMap<String, Object>();
            point.put("date", day.toString());
            point.put("label", weekdayLabel(day));
            point.put("value", value);
            points.add(point);
        }
        return points;
    }

    private Map<String, Long> countBy(String sql) {
        var rows = jdbcTemplate.queryForList(sql);
        var result = new LinkedHashMap<String, Long>();
        for (var row : rows) {
            var bucket = row.get("bucket");
            if (bucket != null) {
                result.put(bucket.toString(), asLong(row.get("total")));
            }
        }
        return result;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private int rate(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round(part * 100.0 / total);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    private String redactText(Object value, String endpointUrl) {
        if (value == null) {
            return null;
        }
        var result = String.valueOf(value);
        for (var secret : endpointSensitiveValues(endpointUrl)) {
            result = result.replace(secret, "[redacted]");
        }
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [redacted]");
        result = SECRET_ASSIGNMENT_PATTERN.matcher(result).replaceAll("$1=[redacted]");
        return result;
    }

    private List<String> endpointSensitiveValues(String endpointUrl) {
        var values = new LinkedHashSet<String>();
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return List.of();
        }
        addSensitiveValue(values, endpointUrl.trim(), false);
        try {
            var uri = URI.create(endpointUrl.trim());
            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                addSensitiveValue(values, uri.getUserInfo(), false);
            }
            var rawPath = uri.getRawPath();
            if (rawPath != null && !rawPath.isBlank()) {
                if ("open.feishu.cn".equalsIgnoreCase(uri.getHost() == null ? "" : uri.getHost())
                    && rawPath.startsWith("/open-apis/bot/v2/hook/")) {
                    addSensitiveValue(values, rawPath.substring("/open-apis/bot/v2/hook/".length()), false);
                }
                for (var part : rawPath.split("/")) {
                    var decoded = decodeOrOriginal(part);
                    if (isTokenLikePathSegment(decoded)) {
                        addSensitiveValue(values, part, false);
                    }
                }
            }
            var rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isBlank()) {
                addSensitiveValue(values, rawQuery, false);
                for (var part : rawQuery.split("&")) {
                    var equalsIndex = part.indexOf('=');
                    if (equalsIndex >= 0 && equalsIndex < part.length() - 1) {
                        addSensitiveValue(values, part.substring(equalsIndex + 1), false);
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            // Keep redaction best-effort for malformed endpoints.
        }
        var result = new ArrayList<>(values);
        result.removeIf(secret -> secret == null || secret.isBlank());
        result.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return result;
    }

    private boolean isTokenLikePathSegment(String value) {
        if (value == null || value.length() < MIN_PATH_SECRET_LENGTH) {
            return false;
        }
        var alphaNumeric = value.chars()
            .filter(Character::isLetterOrDigit)
            .count();
        var hasDigit = value.chars().anyMatch(Character::isDigit);
        return hasDigit && alphaNumeric >= MIN_PATH_SECRET_LENGTH && value.matches("[A-Za-z0-9._~+=-]+");
    }

    private String decodeOrOriginal(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private void addSensitiveValue(LinkedHashSet<String> values, String value, boolean requireLongValue) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (requireLongValue && value.length() < MIN_PATH_SECRET_LENGTH) {
            return;
        }
        values.add(value);
        try {
            var decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (!decoded.equals(value)) {
                values.add(decoded);
            }
        } catch (IllegalArgumentException ex) {
            // Keep the raw value; malformed escape sequences should not hide diagnostics.
        }
    }

    private String stringOrBlank(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String weekdayLabel(LocalDate day) {
        return switch (day.getDayOfWeek()) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
