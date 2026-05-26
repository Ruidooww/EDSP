package com.edsp.transform.standardevent.dedup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class DedupKeyBuilder {
    public String build(
        Long dataSourceId,
        Long schemaTableId,
        String sourceTable,
        Map<String, Object> row,
        List<String> dedupFields,
        String sourceSystem,
        String externalId,
        String eventType,
        OffsetDateTime occurredAt,
        String actor,
        String assetRef,
        String subjectRef
    ) {
        if (dedupFields == null || dedupFields.isEmpty()) {
            return defaultDedupKey(sourceSystem, externalId, eventType, occurredAt, actor, assetRef, subjectRef);
        }
        for (var field : dedupFields) {
            if (stringOrNull(row.get(field)) == null) {
                return null;
            }
        }
        var values = new ArrayList<String>();
        for (var field : dedupFields) {
            values.add(field + "=" + row.get(field));
        }
        return sha256(String.join("|",
            "sync_once",
            "data_source:" + dataSourceId,
            "schema_table:" + schemaTableId,
            "table:" + sourceTable,
            String.join("|", values)
        ));
    }

    private String defaultDedupKey(
        String sourceSystem,
        String externalId,
        String eventType,
        OffsetDateTime occurredAt,
        String actor,
        String assetRef,
        String subjectRef
    ) {
        var source = stringOrDefault(sourceSystem, "external");
        var external = stringOrNull(externalId);
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

    private String stringOrDefault(Object value, String fallback) {
        var text = stringOrNull(value);
        return text == null ? fallback : text;
    }

    private String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
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
