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
public class WeComNotificationAdapter implements NotificationChannelAdapter {
    private final WebhookClient webhookClient;
    private final ObjectMapper objectMapper;

    public WeComNotificationAdapter(WebhookClient webhookClient, ObjectMapper objectMapper) {
        this.webhookClient = webhookClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channelType() {
        return "wecom";
    }

    @Override
    public WebhookDeliveryResult send(Map<String, Object> alert, Map<String, Object> channel, String payloadJson) {
        var endpointUrl = channel.get("endpoint_url") == null ? "" : String.valueOf(channel.get("endpoint_url")).trim();
        validateEndpoint(endpointUrl);

        var rawResult = webhookClient.postJson(endpointUrl, toJson(weComPayload(alert)));
        var result = new WebhookDeliveryResult(
            rawResult.status(),
            rawResult.responseCode(),
            clean(rawResult.responseBody(), endpointUrl),
            clean(rawResult.message(), endpointUrl)
        );
        if (!"success".equals(result.status())) {
            return result;
        }

        var response = parseResponse(result.responseBody());
        var errcode = response.get("errcode");
        if (errcode instanceof Number number && number.intValue() == 0) {
            return new WebhookDeliveryResult("success", result.responseCode(), result.responseBody(), "wecom_delivered");
        }
        if (errcode instanceof String text && "0".equals(text.trim())) {
            return new WebhookDeliveryResult("success", result.responseCode(), result.responseBody(), "wecom_delivered");
        }
        var reason = errcode == null ? "wecom_malformed_response" : "wecom_errcode_" + errcode;
        return new WebhookDeliveryResult("failed", result.responseCode(), result.responseBody(), clean(reason, endpointUrl));
    }

    private void validateEndpoint(String endpointUrl) {
        try {
            var uri = URI.create(endpointUrl);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            var path = uri.getPath() == null ? "" : uri.getPath();
            var query = uri.getQuery() == null ? "" : uri.getQuery();
            if (!"https".equals(scheme)
                || !"qyapi.weixin.qq.com".equals(host)
                || !path.contains("/cgi-bin/webhook/send")
                || !hasQueryKey(query)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_wecom_webhook_url");
            }
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_wecom_webhook_url");
        }
    }

    private String clean(String value, String endpointUrl) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var result = value;
        var key = weComKey(endpointUrl);
        if (!key.isBlank()) {
            result = result.replace(key, "[redacted]");
        }
        return result;
    }

    private String weComKey(String endpointUrl) {
        try {
            var uri = URI.create(endpointUrl);
            var query = uri.getQuery();
            if (query == null || query.isBlank()) {
                return "";
            }
            for (var part : query.split("&")) {
                var equalsIndex = part.indexOf('=');
                if (equalsIndex > 0 && equalsIndex < part.length() - 1 && "key".equals(part.substring(0, equalsIndex))) {
                    return part.substring(equalsIndex + 1);
                }
            }
        } catch (IllegalArgumentException ex) {
            return "";
        }
        return "";
    }

    private boolean hasQueryKey(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        for (var part : query.split("&")) {
            var equalsIndex = part.indexOf('=');
            var key = equalsIndex >= 0 ? part.substring(0, equalsIndex) : part;
            if ("key".equals(key)) {
                return equalsIndex >= 0 && equalsIndex < part.length() - 1;
            }
        }
        return false;
    }

    private Map<String, Object> weComPayload(Map<String, Object> alert) {
        return Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("content", markdown(alert))
        );
    }

    private String markdown(Map<String, Object> alert) {
        var content = new StringBuilder();
        content.append("**EDSP Alert Notification**\n");
        append(content, "Title", alert.get("title"));
        append(content, "Severity", alert.get("severity"));
        append(content, "Status", alert.get("status"));
        append(content, "Source", alert.get("source_system"));
        append(content, "Type", alert.get("alert_type"));
        append(content, "Alert ID", alert.get("id"));
        append(content, "Rule", alert.get("policy_name"));
        append(content, "Sent At", Instant.now().toString());
        return content.toString();
    }

    private void append(StringBuilder content, String label, Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        content.append("> ").append(label).append(": ").append(value).append("\n");
    }

    private Map<String, Object> parseResponse(String responseBody) {
        try {
            return objectMapper.readValue(responseBody == null ? "{}" : responseBody, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize WeCom notification payload", ex);
        }
    }
}
