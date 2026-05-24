package com.edsp.alert.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WebhookClient {
    private static final int MAX_SAVED_TEXT_LENGTH = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;

    public WebhookClient() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build());
    }

    WebhookClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public WebhookDeliveryResult postJson(String endpointUrl, String payloadJson) {
        URI uri;
        try {
            uri = URI.create(endpointUrl);
        } catch (IllegalArgumentException ex) {
            return failed(null, "", "invalid_webhook_url");
        }

        try {
            var request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var status = response.statusCode() >= 200 && response.statusCode() < 300 ? "success" : "failed";
            return new WebhookDeliveryResult(
                status,
                response.statusCode(),
                clean(response.body(), endpointUrl),
                status.equals("success") ? "webhook_delivered" : "webhook_http_" + response.statusCode()
            );
        } catch (java.net.http.HttpTimeoutException ex) {
            return failed(null, endpointUrl, "webhook_timeout");
        } catch (IllegalArgumentException ex) {
            return failed(null, endpointUrl, "invalid_webhook_url");
        } catch (IOException ex) {
            return failed(null, endpointUrl, "webhook_connection_failed: " + ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed(null, endpointUrl, "webhook_interrupted");
        }
    }

    private WebhookDeliveryResult failed(Integer responseCode, String endpointUrl, String message) {
        var cleanMessage = clean(message, endpointUrl);
        return new WebhookDeliveryResult("failed", responseCode, cleanMessage, cleanMessage);
    }

    private String clean(String value, String endpointUrl) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var result = value;
        for (var sensitiveValue : sensitiveValues(endpointUrl)) {
            result = result.replace(sensitiveValue, "[redacted]");
        }
        return truncate(result);
    }

    private List<String> sensitiveValues(String endpointUrl) {
        var values = new ArrayList<String>();
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return values;
        }
        addSensitiveValue(values, endpointUrl, false);
        try {
            var uri = URI.create(endpointUrl);
            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                addSensitiveValue(values, uri.getUserInfo(), false);
            }
            var rawPath = uri.getRawPath();
            if (rawPath != null && !rawPath.isBlank()) {
                for (var part : rawPath.split("/")) {
                    addSensitiveValue(values, part, true);
                }
            }
            var query = uri.getRawQuery();
            if (query != null && !query.isBlank()) {
                for (var part : query.split("&")) {
                    var equalsIndex = part.indexOf('=');
                    if (equalsIndex >= 0 && equalsIndex < part.length() - 1) {
                        addSensitiveValue(values, part.substring(equalsIndex + 1), false);
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            // Invalid URLs are reported without echoing the original value.
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    private void addSensitiveValue(List<String> values, String value, boolean requireLongValue) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (requireLongValue && value.length() < 8) {
            return;
        }
        values.add(value);
        try {
            var decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (!decoded.equals(value)) {
                values.add(decoded);
            }
        } catch (IllegalArgumentException ex) {
            // Keep the raw value; malformed escape sequences should not hide the delivery result.
        }
    }

    private String truncate(String value) {
        return value.length() > MAX_SAVED_TEXT_LENGTH ? value.substring(0, MAX_SAVED_TEXT_LENGTH) : value;
    }
}
