package com.edsp.transform.standardevent.normalize;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public final class TimeValueParser {
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

    public OffsetDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var text = value.trim();
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // Try more common source database formats below.
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
        throw new IllegalArgumentException("Unsupported time format: " + value);
    }
}
