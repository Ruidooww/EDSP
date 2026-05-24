package com.edsp.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertNotificationService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WebhookClient webhookClient;

    public AlertNotificationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        WebhookClient webhookClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.webhookClient = webhookClient;
    }

    public Map<String, Object> send(long alertId, long channelId) {
        var alert = fetchAlert(alertId);
        if (alert == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert_not_found");
        }
        if (!"open".equals(alert.get("status"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alert_not_open");
        }

        var channel = fetchChannel(channelId);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "channel_not_found");
        }
        if (!Boolean.TRUE.equals(channel.get("enabled"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel_disabled");
        }
        if (!"webhook".equalsIgnoreCase(String.valueOf(channel.get("channel_type")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel");
        }

        var endpointUrl = channel.get("endpoint_url") == null ? "" : String.valueOf(channel.get("endpoint_url")).trim();
        if (endpointUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel");
        }

        var payload = payload(alert, channelId);
        var payloadJson = toJson(payload);
        var deliveryResult = webhookClient.postJson(endpointUrl, payloadJson);
        var deliveryId = saveDelivery(channelId, alert, payloadJson, deliveryResult);

        var result = new LinkedHashMap<String, Object>();
        result.put("deliveryId", deliveryId);
        result.put("alertId", alertId);
        result.put("channelId", channelId);
        result.put("status", deliveryResult.status());
        result.put("responseCode", deliveryResult.responseCode());
        result.put("responseBody", deliveryResult.responseBody());
        result.put("message", deliveryResult.message());
        return result;
    }

    private Map<String, Object> fetchAlert(long alertId) {
        var rows = jdbcTemplate.queryForList("""
            select id, title, severity, status, rule_id, subject_type, subject_ref,
                   source_system, external_id, alert_type, occurred_at, actor, asset_ref,
                   policy_name, standard_event_id, alert_decision_id,
                   cast(detail_json as varchar) as detail_json, created_at, updated_at
            from alerts
            where id = ?
            limit 1
            """, alertId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> fetchChannel(long channelId) {
        var rows = jdbcTemplate.queryForList("""
            select id, name, channel_type, endpoint_url, enabled
            from notification_channels
            where id = ?
            limit 1
            """, channelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> payload(Map<String, Object> alert, long channelId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event", "security-alert-platform.alert.notification");
        payload.put("alertId", alert.get("id"));
        payload.put("channelId", channelId);
        payload.put("title", alert.get("title"));
        payload.put("severity", alert.get("severity"));
        payload.put("status", alert.get("status"));
        payload.put("ruleId", alert.get("rule_id"));
        payload.put("subjectType", alert.get("subject_type"));
        payload.put("subjectRef", alert.get("subject_ref"));
        payload.put("sourceSystem", alert.get("source_system"));
        payload.put("externalId", alert.get("external_id"));
        payload.put("alertType", alert.get("alert_type"));
        payload.put("occurredAt", alert.get("occurred_at"));
        payload.put("actor", alert.get("actor"));
        payload.put("assetRef", alert.get("asset_ref"));
        payload.put("policyName", alert.get("policy_name"));
        payload.put("standardEventId", alert.get("standard_event_id"));
        payload.put("decisionId", alert.get("alert_decision_id"));
        payload.put("detail", parseJson(alert.get("detail_json")));
        payload.put("sentAt", Instant.now().toString());
        return payload;
    }

    private long saveDelivery(
        long channelId,
        Map<String, Object> alert,
        String payloadJson,
        WebhookDeliveryResult result
    ) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into notification_deliveries(
                    channel_id, alert_id, title, severity, status, response_code, response_body, payload_json
                )
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, new String[] {"id"});
            statement.setLong(1, channelId);
            statement.setLong(2, ((Number) alert.get("id")).longValue());
            statement.setString(3, String.valueOf(alert.get("title")));
            statement.setString(4, stringOrNull(alert.get("severity")));
            statement.setString(5, result.status());
            if (result.responseCode() == null) {
                statement.setObject(6, null);
            } else {
                statement.setInt(6, result.responseCode());
            }
            statement.setString(7, result.responseBody());
            statement.setString(8, payloadJson);
            return statement;
        }, keyHolder);
        var key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    private Map<String, Object> parseJson(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        try {
            var node = value instanceof byte[] bytes
                ? objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8))
                : objectMapper.readTree(String.valueOf(value));
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize notification payload", ex);
        }
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
