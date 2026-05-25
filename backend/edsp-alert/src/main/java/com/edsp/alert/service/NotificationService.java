package com.edsp.alert.service;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private static final String FEISHU_HOST = "open.feishu.cn";
    private static final String FEISHU_PATH_PREFIX = "/open-apis/bot/v2/hook/";
    private static final NotificationSecretSanitizer SECRET_SANITIZER = new NotificationSecretSanitizer();

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
        return listDeliveries(limit, alertId, null, null, null);
    }

    public List<Map<String, Object>> listDeliveries(
        int limit,
        Long alertId,
        String status,
        String channelType,
        Long channelId
    ) {
        var safeLimit = Math.max(1, Math.min(limit, 200));
        var normalizedStatus = normalizeDeliveryStatus(status);
        var normalizedChannelType = normalizeOptionalChannelType(channelType);
        var filters = new ArrayList<String>();
        var args = new ArrayList<Object>();

        if (alertId != null) {
            filters.add("d.alert_id = ?");
            args.add(alertId);
        }
        if (normalizedStatus != null) {
            filters.add("d.status = ?");
            args.add(normalizedStatus);
        }
        if (normalizedChannelType != null) {
            filters.add("c.channel_type = ?");
            args.add(normalizedChannelType);
        }
        if (channelId != null) {
            filters.add("d.channel_id = ?");
            args.add(channelId);
        }

        var sql = new StringBuilder("""
            select d.id, d.channel_id, c.name as channel_name, c.channel_type,
                   d.alert_id, a.title as alert_title, c.endpoint_url,
                   d.title, d.severity, d.status, d.response_code, d.response_body,
                   cast(d.payload_json as varchar) as payload_json,
                   d.failure_type, d.failure_reason, d.retryable,
                   d.retry_of_delivery_id, d.retry_count,
                   d.created_at
            from notification_deliveries d
            left join notification_channels c on c.id = d.channel_id
            left join alerts a on a.id = d.alert_id
            """);
        if (!filters.isEmpty()) {
            sql.append("where ").append(String.join(" and ", filters)).append("\n");
        }
        sql.append("""
            order by d.created_at desc
            limit ?
            """);
        args.add(safeLimit);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray()).stream()
            .map(this::presentDelivery)
            .toList();
    }

    public Map<String, Object> createChannel(NotificationChannelRequest request) {
        var channelType = normalizeType(request.channelType());
        var endpointUrl = normalizeEndpoint(channelType, request.webhookUrl());
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
        var endpointUrl = normalizeEndpoint(channelType, request.webhookUrl());
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
        result.put("endpoint_masked", SECRET_SANITIZER.maskEndpoint(row.get("endpoint_url")));
        result.remove("endpoint_url");
        return result;
    }

    private Map<String, Object> presentDelivery(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        var endpointUrl = stringOrBlank(row.get("endpoint_url"));
        result.put("response_body", redactNullable(row.get("response_body"), endpointUrl));
        result.put("failure_reason", redactNullable(row.get("failure_reason"), endpointUrl));
        result.put("payload_json", redactPayloadNullable(row.get("payload_json"), endpointUrl));
        result.remove("endpoint_url");
        return result;
    }

    private String redactNullable(Object value, String endpointUrl) {
        return value == null ? null : SECRET_SANITIZER.redactText(String.valueOf(value), endpointUrl);
    }

    private String redactPayloadNullable(Object value, String endpointUrl) {
        return value == null ? null : SECRET_SANITIZER.redactPayloadSecrets(String.valueOf(value), endpointUrl);
    }

    private String configJson(NotificationChannelRequest request) {
        return toJson(SECRET_SANITIZER.sanitizeConfig(request.config(), request.webhookUrl()));
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
        if (!Set.of("webhook", "wecom", "feishu").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel");
        }
        return type;
    }

    private String normalizeOptionalChannelType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeType(value);
    }

    private String normalizeDeliveryStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var status = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("success", "failed").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_delivery_status");
        }
        return status;
    }

    private String normalizeEndpoint(String channelType, String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidEndpointReason(channelType));
        }
        var endpoint = value.trim();
        try {
            var uri = URI.create(endpoint);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ("wecom".equals(channelType)) {
                var path = uri.getPath() == null ? "" : uri.getPath();
                var query = uri.getQuery() == null ? "" : uri.getQuery();
                if (!"https".equals(scheme)
                    || !"qyapi.weixin.qq.com".equals(host)
                    || !path.contains("/cgi-bin/webhook/send")
                    || !hasQueryKey(query)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_wecom_webhook_url");
                }
                return endpoint;
            }
            if ("feishu".equals(channelType)) {
                var path = uri.getPath() == null ? "" : uri.getPath();
                if (!"https".equals(scheme)
                    || !FEISHU_HOST.equals(host)
                    || !path.startsWith(FEISHU_PATH_PREFIX)
                    || !isSingleFeishuTokenPath(path)
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_feishu_webhook_url");
                }
                return endpoint;
            }
            if (!("http".equals(scheme) || "https".equals(scheme)) || host.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_url");
            }
            return endpoint;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidEndpointReason(channelType));
        }
    }

    private String invalidEndpointReason(String channelType) {
        if ("wecom".equals(channelType)) {
            return "invalid_wecom_webhook_url";
        }
        if ("feishu".equals(channelType)) {
            return "invalid_feishu_webhook_url";
        }
        return "invalid_webhook_url";
    }

    private boolean hasQueryKey(String query) {
        return !extractQueryKeyFromQuery(query).isBlank();
    }

    private String extractQueryKeyFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        for (var part : query.split("&")) {
            var equalsIndex = part.indexOf('=');
            var key = equalsIndex >= 0 ? part.substring(0, equalsIndex) : part;
            if ("key".equals(key) && equalsIndex >= 0 && equalsIndex < part.length() - 1) {
                return part.substring(equalsIndex + 1);
            }
        }
        return "";
    }

    private boolean isSingleFeishuTokenPath(String path) {
        var token = path.substring(FEISHU_PATH_PREFIX.length()).trim();
        return !token.isBlank() && !token.contains("/");
    }

    private String stringOrBlank(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
