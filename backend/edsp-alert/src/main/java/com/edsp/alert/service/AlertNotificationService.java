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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertNotificationService {
    private static final int MAX_SAVED_TEXT_LENGTH = 1000;
    private static final NotificationSecretSanitizer SECRET_SANITIZER = new NotificationSecretSanitizer();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;

    public AlertNotificationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        NotificationChannelAdapterRegistry adapterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.adapterRegistry = adapterRegistry;
    }

    public Map<String, Object> send(long alertId, long channelId) {
        return sendInternal(alertId, channelId, null);
    }

    @Transactional
    public Map<String, Object> retryDelivery(long deliveryId) {
        var delivery = fetchDelivery(deliveryId);
        if (delivery == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "delivery_not_found");
        }
        if (!"failed".equals(delivery.get("status"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_not_failed");
        }
        if (!Boolean.TRUE.equals(delivery.get("retryable"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_not_retryable");
        }
        var alertId = numberOrNull(delivery.get("alert_id"));
        if (alertId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_missing_alert");
        }
        var channelId = numberOrNull(delivery.get("channel_id"));
        if (channelId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_missing_channel");
        }

        var result = sendInternal(alertId, channelId, deliveryId);
        jdbcTemplate.update(
            "update notification_deliveries set retry_count = retry_count + 1 where id = ?",
            deliveryId
        );
        result.put("retryOfDeliveryId", deliveryId);
        return result;
    }

    private Map<String, Object> sendInternal(long alertId, long channelId, Long retryOfDeliveryId) {
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
        var adapter = adapterRegistry.find(String.valueOf(channel.get("channel_type")))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel"));
        if ("webhook".equalsIgnoreCase(adapter.channelType()) && isBlank(channel.get("endpoint_url"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel");
        }

        var payload = payload(alert, channelId);
        var payloadJson = toJson(payload);
        var deliveryResult = sanitizeDeliveryResult(
            sendWithAdapter(adapter, alert, channel, payloadJson),
            channel
        );
        var safePayloadJson = SECRET_SANITIZER.redactPayloadSecrets(payloadJson, stringOrNull(channel.get("endpoint_url")));
        var deliveryId = saveDelivery(channelId, alert, safePayloadJson, deliveryResult, retryOfDeliveryId);
        var reliability = reliability(deliveryResult);

        var result = new LinkedHashMap<String, Object>();
        result.put("deliveryId", deliveryId);
        result.put("alertId", alertId);
        result.put("channelId", channelId);
        result.put("status", deliveryResult.status());
        result.put("responseCode", deliveryResult.responseCode());
        result.put("responseBody", deliveryResult.responseBody());
        result.put("message", deliveryResult.message());
        result.put("failureType", reliability.failureType());
        result.put("failureReason", reliability.failureReason());
        result.put("retryable", reliability.retryable());
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

    private Map<String, Object> fetchDelivery(long deliveryId) {
        var rows = jdbcTemplate.queryForList("""
            select id, channel_id, alert_id, status, retryable, retry_count
            from notification_deliveries
            where id = ?
            limit 1
            """, deliveryId);
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
        WebhookDeliveryResult result,
        Long retryOfDeliveryId
    ) {
        var reliability = reliability(result);
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into notification_deliveries(
                    channel_id, alert_id, title, severity, status, response_code, response_body,
                    payload_json, failure_type, failure_reason, retryable, retry_of_delivery_id, retry_count
                )
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, 0)
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
            statement.setString(9, reliability.failureType());
            statement.setString(10, reliability.failureReason());
            statement.setBoolean(11, reliability.retryable());
            if (retryOfDeliveryId == null) {
                statement.setObject(12, null);
            } else {
                statement.setLong(12, retryOfDeliveryId);
            }
            return statement;
        }, keyHolder);
        var key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    private WebhookDeliveryResult sendWithAdapter(
        NotificationChannelAdapter adapter,
        Map<String, Object> alert,
        Map<String, Object> channel,
        String payloadJson
    ) {
        try {
            return adapter.send(alert, channel, payloadJson);
        } catch (ResponseStatusException ex) {
            var reason = ex.getReason() == null ? "unknown_error" : ex.getReason();
            if (reason.startsWith("invalid_") && reason.endsWith("_webhook_url")) {
                return new WebhookDeliveryResult("failed", null, "", reason);
            }
            throw ex;
        }
    }

    private WebhookDeliveryResult sanitizeDeliveryResult(WebhookDeliveryResult result, Map<String, Object> channel) {
        var endpointUrl = stringOrNull(channel.get("endpoint_url"));
        return new WebhookDeliveryResult(
            result.status(),
            result.responseCode(),
            SECRET_SANITIZER.redactText(result.responseBody(), endpointUrl),
            SECRET_SANITIZER.redactText(result.message(), endpointUrl)
        );
    }

    private Reliability reliability(WebhookDeliveryResult result) {
        if ("success".equals(result.status())) {
            return new Reliability(null, null, false);
        }
        var failureType = failureType(result);
        return new Reliability(failureType, savedReason(result, failureType), retryable(failureType));
    }

    private String failureType(WebhookDeliveryResult result) {
        var message = result.message() == null ? "" : result.message();
        var responseBody = result.responseBody() == null ? "" : result.responseBody();
        var evidence = message + " " + responseBody;
        if (message.startsWith("invalid_") && message.endsWith("_webhook_url")) {
            return "invalid_endpoint";
        }
        if ("unsupported_channel".equals(message)) {
            return "unsupported_channel";
        }
        if (evidence.contains("timeout")) {
            return "timeout";
        }
        if (evidence.contains("connection_failed") || evidence.contains("ConnectException")) {
            return "connection_error";
        }
        if (result.responseCode() != null) {
            var code = result.responseCode();
            if (code == 408) {
                return "http_408";
            }
            if (code == 429) {
                return "http_429";
            }
            if (code >= 500 && code <= 599) {
                return "http_5xx";
            }
            if (code >= 400 && code <= 499) {
                return "http_4xx";
            }
        }
        if (message.contains("malformed_response")) {
            return "malformed_response";
        }
        if (message.startsWith("wecom_errcode_")
            || message.startsWith("feishu_code_")
            || message.startsWith("feishu_status_code_")) {
            return "provider_business_error";
        }
        return "unknown_error";
    }

    private String savedReason(WebhookDeliveryResult result, String failureType) {
        var reason = result.message();
        if (reason == null || reason.isBlank()) {
            reason = result.responseBody();
        }
        if (reason == null || reason.isBlank()) {
            reason = failureType;
        }
        return truncate(reason);
    }

    private boolean retryable(String failureType) {
        return switch (failureType) {
            case "timeout", "connection_error", "http_408", "http_429", "http_5xx" -> true;
            default -> false;
        };
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

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isBlank();
    }

    private Long numberOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String truncate(String value) {
        return value.length() > MAX_SAVED_TEXT_LENGTH ? value.substring(0, MAX_SAVED_TEXT_LENGTH) : value;
    }

    private record Reliability(String failureType, String failureReason, boolean retryable) {
    }
}
