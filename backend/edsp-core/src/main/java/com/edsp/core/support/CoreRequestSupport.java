package com.edsp.core.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CoreRequestSupport {
    private static final int DEFAULT_LIMIT_MAX = 500;
    private static final ZoneOffset DEFAULT_ZONE = ZoneOffset.ofHours(8);
    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.S"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SS"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS")
    );
    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

    private final ObjectMapper objectMapper;

    public CoreRequestSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public int safeLimit(int limit) {
        return safeLimit(limit, DEFAULT_LIMIT_MAX);
    }

    public int safeLimit(int limit, int max) {
        return Math.max(1, Math.min(limit, Math.max(1, max)));
    }

    public String jsonOrEmpty(String value, String fieldName) {
        return jsonOrDefault(value, fieldName, "{}");
    }

    public String jsonOrDefault(String value, String fieldName, String defaultValue) {
        var json = (value == null || value.isBlank()) ? defaultValue : value.trim();
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(json));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be valid JSON", ex);
        }
    }

    public OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        var text = value.trim();
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // Try more common local data-source formats below.
        }

        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Try local date-time formats below.
        }

        for (var formatter : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter).atOffset(DEFAULT_ZONE);
            } catch (DateTimeParseException ignored) {
                // Keep trying supported formats.
            }
        }

        for (var formatter : LOCAL_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay().atOffset(DEFAULT_ZONE);
            } catch (DateTimeParseException ignored) {
                // Keep trying supported formats.
            }
        }

        if (text.matches("\\d{10}|\\d{13}")) {
            var epoch = Long.parseLong(text);
            var millis = text.length() == 10 ? epoch * 1000 : epoch;
            return Instant.ofEpochMilli(millis).atOffset(DEFAULT_ZONE);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported time format: " + value);
    }

    public String dedupKey(
        String sourceSystem,
        String externalId,
        String eventType,
        OffsetDateTime occurredAt,
        String actor,
        String assetRef,
        String subjectRef
    ) {
        var source = stringOrDefault(sourceSystem, "external");
        var external = blankToNull(externalId);
        if (external != null) {
            return sha256("external|" + source + "|" + external);
        }
        return sha256(String.join("|",
            "composite",
            source,
            stringOrDefault(eventType, "unknown"),
            occurredAt == null ? "" : occurredAt.toInstant().toString(),
            stringOrDefault(actor, ""),
            stringOrDefault(assetRef, ""),
            stringOrDefault(subjectRef, "")
        ));
    }

    public String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        var text = value.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    public String stringOrDefault(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        var text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? fallback : text;
    }

    public String stringOrNull(Object value) {
        return blankToNull(value == null ? null : String.valueOf(value));
    }

    public Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
