package com.edsp.alert.service;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public NotificationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
        return listDeliveries(limit, null);
    }

    public List<Map<String, Object>> listDeliveries(int limit, Long alertId) {
        var safeLimit = Math.max(1, Math.min(limit, 200));
        if (alertId != null) {
            return jdbcTemplate.queryForList("""
                select d.id, d.channel_id, c.name as channel_name, c.channel_type,
                       d.alert_id, a.title as alert_title,
                       d.title, d.severity, d.status, d.response_code, d.response_body,
                       cast(d.payload_json as varchar) as payload_json,
                       d.created_at
                from notification_deliveries d
                left join notification_channels c on c.id = d.channel_id
                left join alerts a on a.id = d.alert_id
                where d.alert_id = ?
                order by d.created_at desc
                limit ?
                """, alertId, safeLimit);
        }
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
        var channelType = normalizeType(request.channelType());
        var endpointUrl = normalizeWebhookEndpoint(request.webhookUrl());
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into notification_channels(
                    name, channel_type, endpoint_url, description, config_json, enabled, status
                )
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
                """, new String[] {"id"});
            statement.setString(1, request.name());
            statement.setString(2, channelType);
            statement.setString(3, endpointUrl);
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
        var channelType = normalizeType(request.channelType());
        var endpointUrl = normalizeWebhookEndpoint(request.webhookUrl());
        jdbcTemplate.update("""
            update notification_channels
            set name = ?, channel_type = ?, endpoint_url = ?, description = ?,
                config_json = cast(? as jsonb), enabled = ?, status = ?, updated_at = now()
            where id = ?
            """,
            request.name(), channelType, endpointUrl,
            request.description(), configJson(request), request.enabled(),
            request.enabled() ? "ready" : "disabled", id);
        return Map.of("id", id);
    }

    public Map<String, Object> deleteChannel(long id) {
        jdbcTemplate.update("delete from notification_channels where id = ?", id);
        return Map.of("id", id);
    }

    public Map<String, Object> testChannel(long id) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "use_alert_notification_endpoint");
    }

    public Map<String, Object> send(NotificationSendRequest request) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "use_alert_notification_endpoint");
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
        var type = value == null || value.isBlank() ? "webhook" : value.trim().toLowerCase();
        if (!"webhook".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel");
        }
        return type;
    }

    private String normalizeWebhookEndpoint(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_url");
        }
        var endpoint = value.trim();
        try {
            var uri = URI.create(endpoint);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_url");
            }
            return endpoint;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_url");
        }
    }

    private String maskEndpoint(Object endpoint) {
        if (endpoint == null || String.valueOf(endpoint).isBlank()) {
            return "-";
        }
        try {
            var uri = URI.create(String.valueOf(endpoint));
            var port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            var path = uri.getPath() == null || uri.getPath().isBlank() ? "" : "/...";
            return uri.getScheme() + "://" + uri.getHost() + port + path;
        } catch (IllegalArgumentException ex) {
            return "地址格式异常";
        }
    }

}
