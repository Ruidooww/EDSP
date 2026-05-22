package com.edsp.core.service;

import com.edsp.core.support.CoreRequestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SemanticProfilerService {
    private static final Pattern EXTERNAL_ID =
        Pattern.compile("(^id$|.*(eventid|event_id|alert_id|incident_no|external_id|uuid|guid|log_id|record_id).*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJECT_ID =
        Pattern.compile(".*(user_id|account_id|employee_id|asset_id|host_id|device_id|terminal_id|policy_id|rule_id|dept_id|department_id|org_id|organization_id).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OCCURRED_AT =
        Pattern.compile(".*(occur|event_time|create_time|created_at|time|date|timestamp).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEVERITY =
        Pattern.compile(".*(risk_level|severity|level|priority|grade).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE =
        Pattern.compile(".*(title|event_name|alert_name|name|summary|subject|message).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_TYPE =
        Pattern.compile(".*(event_type|risk_type|alert_type|type|category|behavior).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTOR =
        Pattern.compile(".*(user|account|operator|actor|employee|sender|login).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSET =
        Pattern.compile(".*(asset|host|hostname|host_name|device|terminal|ip).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION =
        Pattern.compile(".*(action|operation|operate|op_type|command).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULT =
        Pattern.compile(".*(result|status|outcome).*", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;
    private final CoreRequestSupport support;

    public SemanticProfilerService(JdbcTemplate jdbcTemplate, CoreRequestSupport support) {
        this.jdbcTemplate = jdbcTemplate;
        this.support = support;
    }

    public List<FieldProfile> profileAndPersist(long schemaTableId, List<Map<String, Object>> fields) {
        return fields.stream()
            .map(field -> {
                var profile = profile(field);
                persistIfAllowed(schemaTableId, field, profile);
                return profile;
            })
            .toList();
    }

    public FieldProfile profile(Map<String, Object> field) {
        var fieldName = support.stringOrDefault(field.get("field_name"), "");
        var fieldType = support.stringOrDefault(field.get("field_type"), "");
        var existingSemantic = support.stringOrNull(field.get("semantic_type"));
        var existingConfidence = number(field.get("confidence"), 0);
        var detected = detect(fieldName, fieldType);
        if (existingSemantic != null && existingConfidence > detected.confidence()) {
            return new FieldProfile(
                fieldName,
                existingSemantic,
                existingConfidence,
                "keep existing semantic type: " + existingSemantic,
                standardField(existingSemantic),
                isCandidateKey(fieldName),
                isTimeCandidate(fieldName, fieldType)
            );
        }
        return detected;
    }

    public static String standardField(String semanticType) {
        if (semanticType == null) {
            return null;
        }
        return switch (semanticType) {
            case "external_id" -> "externalId";
            case "event_type" -> "eventType";
            case "occurred_at" -> "occurredAt";
            case "asset_ref" -> "assetRef";
            case "subject_ref" -> "subjectRef";
            default -> semanticType;
        };
    }

    public static Map<String, FieldProfile> byFieldName(List<FieldProfile> profiles) {
        var result = new LinkedHashMap<String, FieldProfile>();
        for (var profile : profiles) {
            result.put(profile.fieldName(), profile);
        }
        return result;
    }

    private FieldProfile detect(String fieldName, String fieldType) {
        if (EXTERNAL_ID.matcher(fieldName).matches() && !SUBJECT_ID.matcher(fieldName).matches()) {
            return profile(fieldName, "external_id", 92, "field name matches external event id patterns");
        }
        if (OCCURRED_AT.matcher(fieldName).matches() || isTimeCandidate(fieldName, fieldType)) {
            return profile(fieldName, "occurred_at", 90, "field name or type matches event time patterns");
        }
        if (SEVERITY.matcher(fieldName).matches()) {
            return profile(fieldName, "severity", 92, "field name matches severity or risk level patterns");
        }
        if (ASSET.matcher(fieldName).matches()) {
            return profile(fieldName, "asset_ref", 84, "field name matches asset or endpoint patterns");
        }
        if (TITLE.matcher(fieldName).matches()) {
            return profile(fieldName, "title", 84, "field name matches alert title or event name patterns");
        }
        if (EVENT_TYPE.matcher(fieldName).matches()) {
            return profile(fieldName, "event_type", 82, "field name matches event type patterns");
        }
        if (ACTOR.matcher(fieldName).matches()) {
            return profile(fieldName, "actor", 86, "field name matches actor or account patterns");
        }
        if (ACTION.matcher(fieldName).matches()) {
            return profile(fieldName, "action", 78, "field name matches action patterns");
        }
        if (RESULT.matcher(fieldName).matches()) {
            return profile(fieldName, "result", 72, "field name matches result or status patterns");
        }
        return profile(fieldName, "detail", 40, "field does not match core alert signals");
    }

    private FieldProfile profile(String fieldName, String semanticType, int confidence, String reason) {
        return new FieldProfile(
            fieldName,
            semanticType,
            confidence,
            reason,
            standardField(semanticType),
            isCandidateKey(fieldName),
            isTimeCandidate(fieldName, "")
        );
    }

    private void persistIfAllowed(long schemaTableId, Map<String, Object> field, FieldProfile profile) {
        var fieldName = profile.fieldName();
        if (hasManualMapping(schemaTableId, fieldName)) {
            return;
        }
        var currentSemantic = support.stringOrNull(field.get("semantic_type"));
        var currentConfidence = number(field.get("confidence"), 0);
        if (currentSemantic == null || currentConfidence < profile.confidence()) {
            jdbcTemplate.update("""
                update schema_fields
                set semantic_type = ?, confidence = ?, description = ?
                where schema_table_id = ? and field_name = ?
                """,
                profile.semanticType(),
                profile.confidence(),
                profile.reason(),
                schemaTableId,
                fieldName);
        }
    }

    private boolean hasManualMapping(long schemaTableId, String fieldName) {
        var count = jdbcTemplate.queryForObject("""
            select count(*)
            from field_mappings
            where schema_table_id = ? and source_field = ?
            """, Long.class, schemaTableId, fieldName);
        return count != null && count > 0;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean isCandidateKey(String fieldName) {
        return EXTERNAL_ID.matcher(fieldName).matches();
    }

    private static boolean isTimeCandidate(String fieldName, String fieldType) {
        var type = fieldType == null ? "" : fieldType.toLowerCase();
        return OCCURRED_AT.matcher(fieldName).matches()
            || type.contains("time")
            || type.contains("date");
    }

    public record FieldProfile(
        String fieldName,
        String semanticType,
        int confidence,
        String reason,
        String standardField,
        boolean candidateKey,
        boolean timeCandidate
    ) {
    }
}
