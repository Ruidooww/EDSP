package com.edsp.alert.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class NotificationSecretSanitizer {
    private static final String REDACTED = "[redacted]";
    private static final int MIN_PATH_SECRET_LENGTH = 16;
    private static final Pattern BEARER_PATTERN = Pattern.compile(
        "(?i)\\bBearer\\s+([A-Za-z0-9._~+/=-]{8,})"
    );
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)\\b(key|token|access_token|accessToken|secret|sign|signature|api_key|apikey|password|passwd|auth|authorization|bearer)\\s*[:=]\\s*([^\\s&\"'{}<>]+)"
    );
    private static final Pattern JSON_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)(\"(?:key|token|access_token|accessToken|secret|sign|signature|api_key|apikey|password|passwd|auth|authorization|bearer)\"\\s*:\\s*\")([^\"]+)(\")"
    );

    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
        "webhookurl",
        "endpointurl",
        "url",
        "key",
        "token",
        "secret",
        "sign",
        "accesstoken",
        "apikey",
        "password",
        "passwd",
        "auth",
        "signature",
        "authorization",
        "bearer"
    );

    public Map<String, Object> sanitizeConfig(Map<String, Object> config, String endpointUrl) {
        if (config == null || config.isEmpty()) {
            return Map.of();
        }
        var secrets = sensitiveValues(endpointUrl);
        var sanitized = new LinkedHashMap<String, Object>();
        config.forEach((key, value) -> {
            if (isSensitiveKey(key)) {
                return;
            }
            var sanitizedValue = sanitizeConfigValue(value, secrets);
            if (sanitizedValue != null) {
                sanitized.put(key, sanitizedValue);
            }
        });
        return sanitized;
    }

    public String maskEndpoint(Object endpoint) {
        if (endpoint == null || String.valueOf(endpoint).isBlank()) {
            return "-";
        }
        try {
            var uri = URI.create(String.valueOf(endpoint).trim());
            var scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            var rawPath = uri.getPath() == null ? "" : uri.getPath();
            if ("open.feishu.cn".equals(host) && rawPath.startsWith("/open-apis/bot/v2/hook/")) {
                return scheme + "://open.feishu.cn/open-apis/bot/v2/hook/...";
            }
            if ("qyapi.weixin.qq.com".equals(host)) {
                return scheme + "://qyapi.weixin.qq.com/...";
            }
            var port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            var userInfo = uri.getRawUserInfo() == null || uri.getRawUserInfo().isBlank() ? "" : "[redacted]@";
            var path = maskedPath(rawPath);
            var query = maskedQuery(uri.getRawQuery());
            return scheme + "://" + userInfo + uri.getHost() + port + path + query;
        } catch (IllegalArgumentException ex) {
            return "invalid_endpoint";
        }
    }

    public String redactText(String value, String endpointUrl) {
        return redactText(value, sensitiveValues(endpointUrl));
    }

    public String redactKnownValues(String value, String endpointUrl) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return redactValues(value, sensitiveValues(endpointUrl));
    }

    public String redactPayloadSecrets(String value, String endpointUrl) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return redactValues(value, payloadSensitiveValues(endpointUrl));
    }

    private String redactValues(String value, Collection<String> sensitiveValues) {
        var result = value;
        var sortedSecrets = new ArrayList<>(sensitiveValues == null ? List.<String>of() : sensitiveValues);
        sortedSecrets.removeIf(secret -> secret == null || secret.isBlank());
        sortedSecrets.sort(Comparator.comparingInt(String::length).reversed());
        for (var secret : sortedSecrets) {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }

    public String redactText(String value, Collection<String> sensitiveValues) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var result = value;
        var sortedSecrets = new ArrayList<>(sensitiveValues == null ? List.<String>of() : sensitiveValues);
        sortedSecrets.removeIf(secret -> secret == null || secret.isBlank());
        sortedSecrets.sort(Comparator.comparingInt(String::length).reversed());
        for (var secret : sortedSecrets) {
            result = result.replace(secret, REDACTED);
        }
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer " + REDACTED);
        result = ASSIGNMENT_PATTERN.matcher(result).replaceAll("$1=" + REDACTED);
        result = JSON_ASSIGNMENT_PATTERN.matcher(result).replaceAll("$1" + REDACTED + "$3");
        return result;
    }

    public Collection<String> sensitiveValues(String endpointUrl) {
        var values = new LinkedHashSet<String>();
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return values;
        }
        addSensitiveValue(values, endpointUrl.trim(), false);
        try {
            var uri = URI.create(endpointUrl.trim());
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                addSensitiveValue(values, uri.getUserInfo(), false);
            }
            var rawPath = uri.getRawPath();
            if (rawPath != null && !rawPath.isBlank()) {
                if ("open.feishu.cn".equals(host) && rawPath.startsWith("/open-apis/bot/v2/hook/")) {
                    addSensitiveValue(values, rawPath.substring("/open-apis/bot/v2/hook/".length()), false);
                }
                for (var part : rawPath.split("/")) {
                    addSensitiveValue(values, part, true);
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
            // Invalid endpoints are reported without echoing the original value.
        }
        values.removeIf(value -> value == null || value.isBlank());
        return values;
    }

    private Collection<String> payloadSensitiveValues(String endpointUrl) {
        var values = new LinkedHashSet<String>();
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return values;
        }
        addSensitiveValue(values, endpointUrl.trim(), false);
        try {
            var uri = URI.create(endpointUrl.trim());
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            var rawPath = uri.getRawPath();
            if ("open.feishu.cn".equals(host)
                && rawPath != null
                && rawPath.startsWith("/open-apis/bot/v2/hook/")) {
                addSensitiveValue(values, rawPath.substring("/open-apis/bot/v2/hook/".length()), false);
            }
            var rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isBlank()) {
                for (var part : rawQuery.split("&")) {
                    var equalsIndex = part.indexOf('=');
                    if (equalsIndex >= 0
                        && equalsIndex < part.length() - 1
                        && isSensitiveKey(part.substring(0, equalsIndex))) {
                        addSensitiveValue(values, part.substring(equalsIndex + 1), false);
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            // Keep payload redaction best-effort for malformed endpoints.
        }
        values.removeIf(value -> value == null || value.isBlank());
        return values;
    }

    private String maskedPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        var parts = rawPath.split("/", -1);
        var masked = new ArrayList<String>();
        for (var part : parts) {
            if (part.isBlank()) {
                masked.add(part);
                continue;
            }
            var decoded = decodeOrOriginal(part);
            masked.add(isTokenLikePathSegment(decoded) ? REDACTED : part);
        }
        return String.join("/", masked);
    }

    private String maskedQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        var parts = new ArrayList<String>();
        for (var part : rawQuery.split("&", -1)) {
            var equalsIndex = part.indexOf('=');
            var key = equalsIndex >= 0 ? part.substring(0, equalsIndex) : part;
            if (isSensitiveKey(key)) {
                parts.add(equalsIndex >= 0 ? key + "=" + REDACTED : key);
            } else {
                parts.add(part);
            }
        }
        return "?" + String.join("&", parts);
    }

    private boolean isTokenLikePathSegment(String value) {
        if (value == null || value.length() < MIN_PATH_SECRET_LENGTH) {
            return false;
        }
        var alphaNumeric = value.chars()
            .filter(character -> Character.isLetterOrDigit(character))
            .count();
        var hasDigit = value.chars().anyMatch(Character::isDigit);
        return hasDigit && alphaNumeric >= MIN_PATH_SECRET_LENGTH && value.matches("[A-Za-z0-9._~+=-]+");
    }

    private Object sanitizeConfigValue(Object value, Collection<String> secrets) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return isSensitiveText(text, secrets) ? null : text;
        }
        if (value instanceof Map<?, ?> map) {
            var nested = new LinkedHashMap<String, Object>();
            map.forEach((nestedKey, nestedValue) -> {
                var key = String.valueOf(nestedKey);
                if (isSensitiveKey(key)) {
                    return;
                }
                var sanitizedValue = sanitizeConfigValue(nestedValue, secrets);
                if (sanitizedValue != null) {
                    nested.put(key, sanitizedValue);
                }
            });
            return nested;
        }
        if (value instanceof Iterable<?> iterable) {
            var items = new ArrayList<Object>();
            for (var item : iterable) {
                var sanitizedValue = sanitizeConfigValue(item, secrets);
                if (sanitizedValue != null) {
                    items.add(sanitizedValue);
                }
            }
            return items;
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        var normalized = normalizeKey(key);
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private boolean isSensitiveText(String text, Collection<String> secrets) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (var secret : secrets) {
            if (secret != null && !secret.isBlank() && text.contains(secret)) {
                return true;
            }
        }
        var lowered = text.toLowerCase(Locale.ROOT);
        return lowered.contains("qyapi.weixin.qq.com")
            || lowered.contains("/open-apis/bot/v2/hook/")
            || lowered.contains("key=")
            || lowered.contains("token=")
            || lowered.contains("access_token=")
            || lowered.contains("secret=")
            || lowered.contains("signature=")
            || lowered.contains("authorization:")
            || lowered.contains("authorization=")
            || lowered.contains("bearer ");
    }

    private String normalizeKey(String key) {
        return key == null
            ? ""
            : key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String decodeOrOriginal(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private void addSensitiveValue(Set<String> values, String value, boolean requireLongValue) {
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
}
