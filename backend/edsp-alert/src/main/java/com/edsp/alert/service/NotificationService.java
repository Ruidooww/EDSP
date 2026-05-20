package com.edsp.alert.service;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public NotificationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public List<Map<String, Object>> listChannels() {
        return jdbcTemplate.queryForList("""
            select id, name, channel_type, endpoint_url, description, enabled, status,
                   last_test_status, last_test_message, last_test_at, created_at, updated_at
            from notification_channels
            order by updated_at desc
            """).stream().map(this::presentChannel).toList();
    }

    public List<Map<String, Object>> listDeliveries(int limit) {
        var safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList("""
            select d.id, d.channel_id, c.name as channel_name, c.channel_type,
                   d.alert_id, a.title as alert_title,
                   d.title, d.severity, d.status, d.response_code, d.response_body,
                   cast(d.payload_json as varchar) as payload_json,
                   d.created_at
            from notification_deliveries d
            left join notification_channels c on c.id = d.channel_id
            left join alerts a on a.id = d.alert_id
            order by d.created_at desc
            limit ?
            """, safeLimit);
    }

    public Map<String, Object> createChannel(NotificationChannelRequest request) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into notification_channels(
                    name, channel_type, endpoint_url, description, config_json, enabled, status
                )
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
                """, new String[] {"id"});
            statement.setString(1, request.name());
            statement.setString(2, normalizeType(request.channelType()));
            statement.setString(3, normalizeOptional(request.webhookUrl()));
            statement.setString(4, request.description());
            statement.setString(5, configJson(request));
            statement.setBoolean(6, request.enabled());
            statement.setString(7, request.enabled() ? "ready" : "disabled");
            return statement;
        }, keyHolder);
        var idValue = keyHolder.getKey();
        var id = idValue == null ? 0 : idValue.longValue();
        return Map.of("id", id);
    }

    public Map<String, Object> updateChannel(long id, NotificationChannelRequest request) {
        jdbcTemplate.update("""
            update notification_channels
            set name = ?, channel_type = ?, endpoint_url = ?, description = ?,
                config_json = cast(? as jsonb), enabled = ?, status = ?, updated_at = now()
            where id = ?
            """,
            request.name(), normalizeType(request.channelType()), normalizeOptional(request.webhookUrl()),
            request.description(), configJson(request), request.enabled(),
            request.enabled() ? "ready" : "disabled", id);
        return Map.of("id", id);
    }

    public Map<String, Object> deleteChannel(long id) {
        jdbcTemplate.update("delete from notification_channels where id = ?", id);
        return Map.of("id", id);
    }

    public Map<String, Object> testChannel(long id) {
        var channel = fetchChannel(id);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event", "security-alert-platform.notification.test");
        payload.put("title", "数据安全预警分析平台通知通道测试");
        payload.put("message", "这是一条来自数据安全预警分析平台的测试消息。");
        payload.put("severity", "info");
        payload.put("channelId", id);
        payload.put("sentAt", Instant.now().toString());

        var result = dispatch(channel, payload);
        jdbcTemplate.update("""
            update notification_channels
            set last_test_status = ?, last_test_message = ?, last_test_at = ?, status = ?, updated_at = now()
            where id = ?
            """,
            result.get("status"), result.get("message"), Timestamp.from(Instant.now()),
            "success".equals(result.get("status")) ? "ready" : "error", id);
        return result;
    }

    public Map<String, Object> send(NotificationSendRequest request) {
        var channels = targetChannels(request.channelIds());
        var results = new ArrayList<Map<String, Object>>();
        for (var channel : channels) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("event", "security-alert-platform.alert.notification");
            payload.put("alertId", request.alertId());
            payload.put("title", request.title());
            payload.put("message", request.message());
            payload.put("severity", request.severity());
            payload.put("detail", request.detail());
            payload.put("sentAt", Instant.now().toString());

            var result = dispatch(channel, payload);
            saveDelivery(channel, request, payload, result);
            results.add(result);
        }
        var success = results.stream().filter(result -> "success".equals(result.get("status"))).count();
        return Map.of(
            "total", results.size(),
            "success", success,
            "failed", results.size() - success,
            "results", results
        );
    }

    private Map<String, Object> dispatch(Map<String, Object> channel, Map<String, Object> payload) {
        var result = new LinkedHashMap<String, Object>();
        result.put("channelId", channel.get("id"));
        result.put("channelName", channel.get("name"));

        var type = String.valueOf(channel.get("channel_type"));
        if (!"webhook".equalsIgnoreCase(type)) {
            result.put("status", "success");
            result.put("message", channelTypeName(type) + "演示发送成功");
            result.put("responseCode", 200);
            result.put("responseBody", "demo delivery accepted");
            return result;
        }

        var url = channel.get("endpoint_url") == null ? "" : String.valueOf(channel.get("endpoint_url"));
        if (url.isBlank()) {
            result.put("status", "failed");
            result.put("message", "Webhook 地址为空");
            return result;
        }

        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var success = response.statusCode() >= 200 && response.statusCode() < 300;
            result.put("status", success ? "success" : "failed");
            result.put("message", success ? "发送成功" : "发送失败，HTTP " + response.statusCode());
            result.put("responseCode", response.statusCode());
            result.put("responseBody", truncate(response.body()));
        } catch (IllegalArgumentException ex) {
            result.put("status", "failed");
            result.put("message", "Webhook 地址格式不正确");
        } catch (IOException ex) {
            result.put("status", "failed");
            result.put("message", "Webhook 连接失败：" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result.put("status", "failed");
            result.put("message", "Webhook 发送被中断");
        }
        return result;
    }

    private void saveDelivery(
        Map<String, Object> channel,
        NotificationSendRequest request,
        Map<String, Object> payload,
        Map<String, Object> result
    ) {
        jdbcTemplate.update("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body, payload_json
            )
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """,
            channel.get("id"), request.alertId(), request.title(), request.severity(), result.get("status"),
            result.get("responseCode"), result.get("responseBody"), toJson(payload));
    }

    private List<Map<String, Object>> targetChannels(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return jdbcTemplate.queryForList("""
                select id, name, channel_type, endpoint_url
                from notification_channels
                where enabled = true
                order by id
                """);
        }
        var channels = new ArrayList<Map<String, Object>>();
        for (var id : ids) {
            channels.add(fetchChannel(id));
        }
        return channels;
    }

    private Map<String, Object> fetchChannel(long id) {
        return jdbcTemplate.queryForMap("""
            select id, name, channel_type, endpoint_url, enabled
            from notification_channels
            where id = ? and enabled = true
            """, id);
    }

    private Map<String, Object> presentChannel(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        result.put("endpoint_masked", maskEndpoint(row.get("endpoint_url")));
        result.remove("endpoint_url");
        return result;
    }

    private String configJson(NotificationChannelRequest request) {
        var config = new LinkedHashMap<String, Object>();
        config.putAll(request.config());
        if (request.webhookUrl() != null && !request.webhookUrl().isBlank()) {
            config.put("webhookUrl", request.webhookUrl().trim());
        }
        return toJson(config);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String normalizeType(String value) {
        return value == null || value.isBlank() ? "webhook" : value.trim().toLowerCase();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String maskEndpoint(Object endpoint) {
        if (endpoint == null || String.valueOf(endpoint).isBlank()) {
            return "-";
        }
        try {
            var uri = URI.create(String.valueOf(endpoint));
            var port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            var path = uri.getPath() == null || uri.getPath().isBlank() ? "" : uri.getPath();
            return uri.getScheme() + "://" + uri.getHost() + port + path;
        } catch (IllegalArgumentException ex) {
            return "地址格式异常";
        }
    }

    private String channelTypeName(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "wecom" -> "企业微信";
            case "feishu" -> "飞书";
            case "sms" -> "短信";
            case "email" -> "邮件";
            case "webhook" -> "Webhook";
            default -> "通知通道";
        };
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
