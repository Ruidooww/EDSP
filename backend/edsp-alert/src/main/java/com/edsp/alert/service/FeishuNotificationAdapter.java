package com.edsp.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FeishuNotificationAdapter implements NotificationChannelAdapter {
    private static final String FEISHU_HOST = "open.feishu.cn";
    private static final String FEISHU_PATH_PREFIX = "/open-apis/bot/v2/hook/";

    private final WebhookClient webhookClient;
    private final ObjectMapper objectMapper;

    public FeishuNotificationAdapter(WebhookClient webhookClient, ObjectMapper objectMapper) {
        this.webhookClient = webhookClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channelType() {
        return "feishu";
    }

    @Override
    public WebhookDeliveryResult send(Map<String, Object> alert, Map<String, Object> channel, String payloadJson) {
        var endpointUrl = channel.get("endpoint_url") == null ? "" : String.valueOf(channel.get("endpoint_url")).trim();
        var token = validateEndpoint(endpointUrl);

        var rawResult = webhookClient.postJson(endpointUrl, toJson(feishuPayload(alert)));
        var result = new WebhookDeliveryResult(
            rawResult.status(),
            rawResult.responseCode(),
            clean(rawResult.responseBody(), token),
            clean(rawResult.message(), token)
        );
        if (!"success".equals(result.status())) {
            var reason = result.responseCode() == null
                ? "feishu_malformed_response"
                : "feishu_status_code_" + result.responseCode();
            return new WebhookDeliveryResult("failed", result.responseCode(), result.responseBody(), reason);
        }

        var response = parseResponse(result.responseBody());
        if (isZero(response.get("StatusCode")) || isZero(response.get("code"))) {
            return new WebhookDeliveryResult("success", result.responseCode(), result.responseBody(), "feishu_delivered");
        }
        if (response.containsKey("StatusCode")) {
            return new WebhookDeliveryResult(
                "failed",
                result.responseCode(),
                result.responseBody(),
                clean("feishu_status_code_" + response.get("StatusCode"), token)
            );
        }
        if (response.containsKey("code")) {
            return new WebhookDeliveryResult(
                "failed",
                result.responseCode(),
                result.responseBody(),
                clean("feishu_code_" + response.get("code"), token)
            );
        }
        return new WebhookDeliveryResult(
            "failed",
            result.responseCode(),
            result.responseBody(),
            "feishu_malformed_response"
        );
    }

    private String validateEndpoint(String endpointUrl) {
        try {
            var uri = URI.create(endpointUrl);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            var path = uri.getPath() == null ? "" : uri.getPath();
            if (!"https".equals(scheme)
                || !FEISHU_HOST.equals(host)
                || !path.startsWith(FEISHU_PATH_PREFIX)
                || uri.getQuery() != null
                || uri.getFragment() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_feishu_webhook_url");
            }
            var token = path.substring(FEISHU_PATH_PREFIX.length()).trim();
            if (token.isBlank() || token.contains("/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_feishu_webhook_url");
            }
            return token;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_feishu_webhook_url");
        }
    }

    private Map<String, Object> feishuPayload(Map<String, Object> alert) {
        return Map.of(
            "msg_type", "text",
            "content", Map.of("text", text(alert))
        );
    }

    private String text(Map<String, Object> alert) {
        var content = new StringBuilder();
        content.append("EDSP Alert Notification\n");
        append(content, "Title", alert.get("title"));
        append(content, "Severity", alert.get("severity"));
        append(content, "Rule", firstNonBlank(alert.get("policy_name"), alert.get("rule_id")));
        append(content, "Subject", subject(alert));
        append(content, "Asset", alert.get("asset_ref"));
        append(content, "Occurred At", alert.get("occurred_at"));
        append(content, "Alert ID", alert.get("id"));
        append(content, "Sent At", Instant.now().toString());
        return content.toString();
    }

    private Object subject(Map<String, Object> alert) {
        var subjectType = stringOrBlank(alert.get("subject_type"));
        var subjectRef = stringOrBlank(alert.get("subject_ref"));
        if (subjectType.isBlank()) {
            return subjectRef;
        }
        if (subjectRef.isBlank()) {
            return subjectType;
        }
        return subjectType + ":" + subjectRef;
    }

    private Object firstNonBlank(Object first, Object second) {
        return stringOrBlank(first).isBlank() ? second : first;
    }

    private void append(StringBuilder content, String label, Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        content.append(label).append(": ").append(value).append("\n");
    }

    private boolean isZero(Object value) {
        if (value instanceof Number number) {
            return number.intValue() == 0;
        }
        return value instanceof String text && "0".equals(text.trim());
    }

    private Map<String, Object> parseResponse(String responseBody) {
        try {
            return objectMapper.readValue(responseBody == null ? "{}" : responseBody, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private String clean(String value, String token) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return token == null || token.isBlank() ? value : value.replace(token, "[redacted]");
    }

    private String stringOrBlank(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize Feishu notification payload", ex);
        }
    }
}
