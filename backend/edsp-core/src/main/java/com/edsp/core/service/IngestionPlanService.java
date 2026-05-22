package com.edsp.core.service;

import com.edsp.core.dto.IngestionPlanGenerateRequest;
import com.edsp.core.dto.IngestionPlanShadowValidationRequest;
import com.edsp.core.dto.IngestionPlanStatusRequest;
import com.edsp.core.service.SemanticProfilerService.FieldProfile;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionPlanService {
    private static final String PLAN_VERSION = "ingestion-plan-v1";
    private static final String GENERATED_BY = "database-intelligence-mvp";
    private static final String INGESTION_MODE = "database_polling";
    private static final int DEFAULT_SHADOW_SAMPLE_LIMIT = 50;
    private static final int MAX_SHADOW_SAMPLE_LIMIT = 100;
    private static final Set<String> STATUS_WHITELIST = Set.of(
        "suggested",
        "review_required",
        "approved",
        "shadow_ready",
        "rejected"
    );
    private static final Set<String> SHADOW_VALIDATION_STATUSES = Set.of("approved", "shadow_ready");
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
        "suggested", Set.of("review_required", "approved", "rejected"),
        "review_required", Set.of("approved", "rejected"),
        "approved", Set.of("shadow_ready", "rejected"),
        "shadow_ready", Set.of("rejected")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;
    private final SemanticProfilerService profiler;
    private final TemplateMatcherService matcher;

    public IngestionPlanService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support,
        SemanticProfilerService profiler,
        TemplateMatcherService matcher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
        this.profiler = profiler;
        this.matcher = matcher;
    }

    public List<Map<String, Object>> list(Long dataSourceId, String status) {
        var normalizedStatus = normalizeStatusOrNull(status);
        if (normalizedStatus != null) {
            validateStatus(normalizedStatus);
        }
        if (dataSourceId != null && normalizedStatus != null) {
            return planRows(jdbcTemplate.queryForList("""
                select ip.id, ip.data_source_id, ds.name as data_source_name, ip.scan_run_id,
                       ip.name, ip.status, ip.plan_json, ip.created_at, ip.updated_at
                from ingestion_plans ip
                join data_sources ds on ds.id = ip.data_source_id
                where ip.data_source_id = ? and ip.status = ?
                order by ip.updated_at desc, ip.id desc
                """, dataSourceId, normalizedStatus));
        }
        if (dataSourceId != null) {
            return planRows(jdbcTemplate.queryForList("""
                select ip.id, ip.data_source_id, ds.name as data_source_name, ip.scan_run_id,
                       ip.name, ip.status, ip.plan_json, ip.created_at, ip.updated_at
                from ingestion_plans ip
                join data_sources ds on ds.id = ip.data_source_id
                where ip.data_source_id = ?
                order by ip.updated_at desc, ip.id desc
                """, dataSourceId));
        }
        if (normalizedStatus != null) {
            return planRows(jdbcTemplate.queryForList("""
                select ip.id, ip.data_source_id, ds.name as data_source_name, ip.scan_run_id,
                       ip.name, ip.status, ip.plan_json, ip.created_at, ip.updated_at
                from ingestion_plans ip
                join data_sources ds on ds.id = ip.data_source_id
                where ip.status = ?
                order by ip.updated_at desc, ip.id desc
                """, normalizedStatus));
        }
        return planRows(jdbcTemplate.queryForList("""
            select ip.id, ip.data_source_id, ds.name as data_source_name, ip.scan_run_id,
                   ip.name, ip.status, ip.plan_json, ip.created_at, ip.updated_at
            from ingestion_plans ip
            join data_sources ds on ds.id = ip.data_source_id
            order by ip.updated_at desc, ip.id desc
            """));
    }

    @Transactional
    public List<Map<String, Object>> generate(IngestionPlanGenerateRequest request) {
        if (request == null || request.dataSourceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataSourceId is required");
        }
        var dataSourceId = request.dataSourceId();
        ensureDataSourceExists(dataSourceId);

        var scanRun = loadScanRun(dataSourceId, request.scanRunId());
        var scanRunId = scanRun == null ? request.scanRunId() : scanRun.id();
        var coverage = coverage(scanRun);
        var tables = loadSourceTables(dataSourceId, scanRunId);
        var generated = new ArrayList<Map<String, Object>>();
        for (var table : tables) {
            var templateMatch = matcher.match(table.tableName(), table.category(), table.fieldNames());
            if (!templateMatch.mainPlanCandidate()) {
                continue;
            }
            var plan = buildPlan(table, templateMatch, coverage);
            var action = support.stringOrDefault(plan.get("recommendedAction"), "manual_review");
            var status = "shadow_validate".equals(action) ? "suggested" : "review_required";
            var name = table.tableName() + " ingestion plan";
            var planId = upsertPlan(dataSourceId, scanRunId, name, status, plan);
            generated.add(planRow(planId));
        }
        return generated;
    }

    @Transactional
    public Map<String, Object> updateStatus(long id, IngestionPlanStatusRequest request) {
        var targetStatus = normalizeStatusOrNull(request == null ? null : request.status());
        validateStatus(targetStatus);
        var rows = jdbcTemplate.queryForList("""
            select id, data_source_id, scan_run_id, name, status, plan_json, created_at, updated_at
            from ingestion_plans
            where id = ?
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + id);
        }
        var currentStatus = support.stringOrDefault(rows.get(0).get("status"), "");
        if (!currentStatus.equals(targetStatus)
            && !ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(targetStatus)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid ingestion plan status transition: " + currentStatus + " -> " + targetStatus
            );
        }
        jdbcTemplate.update("""
            update ingestion_plans
            set status = ?, updated_at = now()
            where id = ?
            """, targetStatus, id);
        return planRow(jdbcTemplate.queryForMap("""
            select id, data_source_id, scan_run_id, name, status, plan_json, created_at, updated_at
            from ingestion_plans
            where id = ?
            """, id));
    }

    // Shadow Precheck only: no writes to standard_events/alert_decisions/alerts and no notifications.
    public Map<String, Object> shadowValidate(long id, IngestionPlanShadowValidationRequest request) {
        var rows = jdbcTemplate.queryForList("""
            select ip.id, ip.data_source_id, ds.name as data_source_name, ip.scan_run_id,
                   ip.name, ip.status, ip.plan_json, ip.created_at, ip.updated_at
            from ingestion_plans ip
            join data_sources ds on ds.id = ip.data_source_id
            where ip.id = ?
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + id);
        }

        var row = rows.get(0);
        var status = support.stringOrDefault(row.get("status"), "");
        if (!SHADOW_VALIDATION_STATUSES.contains(status)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Ingestion plan must be approved or shadow_ready before Shadow Precheck: " + status
            );
        }

        var plan = parsePlan(row.get("plan_json"));
        var checks = new ArrayList<Map<String, Object>>();
        var blockers = new LinkedHashSet<String>();
        var warnings = new LinkedHashSet<String>();
        var dataSourceId = support.number(row.get("data_source_id"));
        var schemaTableId = longValue(plan.get("schemaTableId"));
        var sourceFields = schemaTableId == null ? Map.<String, Object>of() : loadActiveFieldSamples(schemaTableId);
        var fieldMappings = fieldMappingSources(plan);

        addCheck(checks, "plan_status", "passed", "Plan is approved for Shadow Precheck.");
        if (INGESTION_MODE.equals(support.stringOrDefault(plan.get("mode"), ""))) {
            addCheck(checks, "plan_mode", "passed", "Plan uses database polling mode.");
        } else {
            blockers.add("unsupported_plan_mode");
            addCheck(checks, "plan_mode", "failed", "Only database_polling plans can run Shadow Precheck.", "unsupported_plan_mode");
        }

        validateSourceTable(dataSourceId, schemaTableId, checks, blockers);
        validateSourceFields(fieldMappings, sourceFields, checks, blockers);
        validateRequiredFields(plan, checks, blockers);
        validateDedupStrategy(plan, sourceFields.keySet(), checks, blockers);
        validateSyncGuard(plan, checks, blockers);

        var planRisks = stringList(plan.get("risks"));
        if (planRisks.isEmpty()) {
            addCheck(checks, "plan_risks", "passed", "Plan does not report extra risk flags.");
        } else {
            warnings.addAll(planRisks);
            addCheck(checks, "plan_risks", "warning", "Plan carries risk flags.", (List<String>) null, planRisks);
        }

        var result = blockers.isEmpty() ? (warnings.isEmpty() ? "passed" : "warning") : "blocked";
        var report = new LinkedHashMap<String, Object>();
        report.put("planId", id);
        report.put("dataSourceId", dataSourceId);
        report.put("dataSourceName", support.stringOrNull(row.get("data_source_name")));
        report.put("planStatus", status);
        report.put("result", result);
        report.put("statusRecommendation", blockers.isEmpty() ? "shadow_ready" : "manual_review");
        report.put("checkedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        report.put("sampleLimit", shadowSampleLimit(request));
        report.put("mainTable", support.stringOrNull(plan.get("mainTable")));
        report.put("templateKey", support.stringOrNull(templateKey(plan)));
        report.put("mappedFieldCount", fieldMappings.size());
        report.put("blockers", new ArrayList<>(blockers));
        report.put("warnings", new ArrayList<>(warnings));
        report.put("standardEventPreview", standardEventPreview(fieldMappings, sourceFields));
        report.put("checks", checks);
        return report;
    }

    private Map<String, Object> buildPlan(
        SourceTable table,
        TemplateMatcherService.TemplateMatch templateMatch,
        Coverage coverage
    ) {
        var fieldMappings = new ArrayList<Map<String, Object>>();
        var fieldEvidence = new LinkedHashMap<String, Object>();
        var mappedStandards = new LinkedHashSet<String>();
        var mappingConfidenceValues = new ArrayList<Integer>();

        var profiles = profiler.profileAndPersist(table.id(), table.fields().stream().map(SourceField::row).toList());
        for (var index = 0; index < table.fields().size(); index++) {
            var field = table.fields().get(index);
            var profile = profiles.get(index);
            var existingMappings = table.existingMappings().getOrDefault(field.fieldName(), List.of());
            if (existingMappings.isEmpty()) {
                addInferredEvidence(field, profile, fieldEvidence);
                if (profile.standardField() != null) {
                    fieldMappings.add(fieldMapping(field.fieldName(), profile.standardField(), profile.confidence(), "semantic_profile"));
                    mappedStandards.add(profile.standardField());
                    mappingConfidenceValues.add(profile.confidence());
                }
            } else {
                addExistingMappingEvidence(field, profile, existingMappings, fieldEvidence);
                for (var mapping : existingMappings) {
                    fieldMappings.add(fieldMapping(
                        field.fieldName(),
                        mapping.standardField(),
                        field.confidenceOr(95),
                        "existing_mapping",
                        mapping.transformRule()
                    ));
                    mappedStandards.add(mapping.standardField());
                    mappingConfidenceValues.add(field.confidenceOr(95));
                }
            }
        }

        var cursorField = firstSourceForStandard(fieldMappings, "occurredAt");
        var idField = firstSourceForStandard(fieldMappings, "externalId");
        var dedup = dedupStrategy(idField, fieldMappings);
        var requiredFieldsMissing = new ArrayList<String>();
        var risks = new ArrayList<String>();
        if (cursorField == null) {
            requiredFieldsMissing.add("occurred_at");
            risks.add("missing_occurred_at");
        }
        var dedupInsufficient = Boolean.FALSE.equals(dedup.get("stable"));
        if (dedupInsufficient) {
            requiredFieldsMissing.add("dedup_key_source_insufficient");
            risks.add("dedup_key_source_insufficient");
        }
        if (coverage.limited()) {
            risks.add("limited_scan");
        }

        var mappingConfidence = mappingConfidence(mappingConfidenceValues);
        var confidence = confidence(
            templateMatch.confidence(),
            mappingConfidence,
            coverage.confidence(),
            cursorField == null,
            dedupInsufficient
        );
        var recommendedAction = recommendedAction(coverage, mappingConfidence, confidence, requiredFieldsMissing);

        var plan = new LinkedHashMap<String, Object>();
        plan.put("version", PLAN_VERSION);
        plan.put("generatedBy", GENERATED_BY);
        plan.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        plan.put("mode", INGESTION_MODE);
        plan.put("confidence", confidence);
        plan.put("coverageConfidence", coverage.confidence());
        plan.put("mappingConfidence", mappingConfidence);
        plan.put("mainTable", table.tableName());
        plan.put("schemaTableId", table.id());
        plan.put("cursorField", cursorField);
        plan.put("idField", idField);
        plan.put("dedupStrategy", dedup);
        plan.put("fieldMappings", fieldMappingObject(fieldMappings));
        plan.put("fieldMappingDetails", fieldMappings);
        plan.put("fieldEvidence", fieldEvidence);
        plan.put("templateMatch", templateMatch(templateMatch));
        plan.put("syncStrategy", syncStrategy(cursorField));
        plan.put("risks", risks);
        plan.put("requiredFieldsMissing", requiredFieldsMissing);
        plan.put("recommendedAction", recommendedAction);
        return plan;
    }

    private int shadowSampleLimit(IngestionPlanShadowValidationRequest request) {
        var requested = request == null || request.sampleLimit() == null
            ? DEFAULT_SHADOW_SAMPLE_LIMIT
            : request.sampleLimit();
        return support.safeLimit(requested, MAX_SHADOW_SAMPLE_LIMIT);
    }

    private Map<String, Object> loadActiveFieldSamples(Long schemaTableId) {
        var rows = jdbcTemplate.queryForList("""
            select field_name, sample_value
            from schema_fields
            where schema_table_id = ?
              and coalesce(lifecycle_status, 'active') = 'active'
            order by ordinal_position nulls last, id
            """, schemaTableId);
        var fields = new LinkedHashMap<String, Object>();
        for (var row : rows) {
            var fieldName = support.stringOrNull(row.get("field_name"));
            if (fieldName != null) {
                fields.put(fieldName, row.get("sample_value"));
            }
        }
        return fields;
    }

    private void validateSourceTable(
        Long dataSourceId,
        Long schemaTableId,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (dataSourceId == null || schemaTableId == null) {
            blockers.add("source_table_missing");
            addCheck(checks, "source_table", "failed", "Plan does not reference a source table.", "source_table_missing");
            return;
        }
        var rows = jdbcTemplate.queryForList("""
            select id
            from schema_tables
            where id = ?
              and data_source_id = ?
              and coalesce(lifecycle_status, 'active') = 'active'
            """, schemaTableId, dataSourceId);
        if (rows.isEmpty()) {
            blockers.add("source_table_missing");
            addCheck(checks, "source_table", "failed", "Plan source table is not active or does not belong to this data source.",
                "source_table_missing");
            return;
        }
        addCheck(checks, "source_table", "passed", "Plan source table is active.");
    }

    private void validateSourceFields(
        Map<String, String> fieldMappings,
        Map<String, Object> sourceFields,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (fieldMappings.isEmpty()) {
            blockers.add("field_mappings_missing");
            addCheck(checks, "source_fields", "failed", "Plan does not include field mappings.", "field_mappings_missing");
            return;
        }
        var missing = fieldMappings.keySet().stream()
            .filter(field -> !sourceFields.containsKey(field))
            .toList();
        if (!missing.isEmpty()) {
            blockers.add("mapped_source_fields_missing");
            addCheck(checks, "source_fields", "failed", "Some mapped source fields are no longer active.",
                "mapped_source_fields_missing", missing);
            return;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("mappedFieldCount", fieldMappings.size());
        addCheck(checks, "source_fields", "passed", "All mapped source fields are active.", (List<String>) null, details);
    }

    private void validateRequiredFields(
        Map<String, Object> plan,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        var requiredMissing = stringList(plan.get("requiredFieldsMissing"));
        var checkBlockers = new ArrayList<String>();
        if (requiredMissing.contains("occurred_at") || support.stringOrNull(plan.get("cursorField")) == null) {
            checkBlockers.add("missing_occurred_at");
        }
        if (requiredMissing.contains("dedup_key_source_insufficient")) {
            checkBlockers.add("dedup_key_source_insufficient");
        }
        if (!checkBlockers.isEmpty()) {
            blockers.addAll(checkBlockers);
            addCheck(checks, "required_fields", "failed", "Plan is missing required fields for Shadow Precheck.",
                checkBlockers, null);
            return;
        }
        addCheck(checks, "required_fields", "passed", "Required event time and dedup inputs are present.");
    }

    @SuppressWarnings("unchecked")
    private void validateDedupStrategy(
        Map<String, Object> plan,
        Set<String> sourceFields,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (!(plan.get("dedupStrategy") instanceof Map<?, ?> strategy)) {
            blockers.add("dedup_strategy_missing");
            addCheck(checks, "dedup_strategy", "failed", "Plan does not include a dedup strategy.", "dedup_strategy_missing");
            return;
        }
        var type = support.stringOrNull(strategy.get("type"));
        var fields = stringList(strategy.get("fields"));
        var missing = fields.stream().filter(field -> !sourceFields.contains(field)).toList();
        if (fields.isEmpty()) {
            blockers.add("dedup_strategy_missing");
            addCheck(checks, "dedup_strategy", "failed", "Dedup strategy has no source fields.", "dedup_strategy_missing");
            return;
        }
        if (!missing.isEmpty()) {
            blockers.add("dedup_fields_missing");
            addCheck(checks, "dedup_strategy", "failed", "Dedup strategy references inactive source fields.",
                "dedup_fields_missing", missing);
            return;
        }
        if ("composite".equals(type) && !boolValue(strategy.get("stable"))) {
            blockers.add("dedup_key_source_insufficient");
            addCheck(checks, "dedup_strategy", "failed", "Composite dedup strategy is not stable.",
                "dedup_key_source_insufficient");
            return;
        }
        addCheck(checks, "dedup_strategy", "passed", "Dedup strategy can be evaluated during Shadow Precheck.");
    }

    @SuppressWarnings("unchecked")
    private void validateSyncGuard(
        Map<String, Object> plan,
        List<Map<String, Object>> checks,
        Set<String> blockers
    ) {
        if (!(plan.get("syncStrategy") instanceof Map<?, ?> strategy)) {
            blockers.add("sync_guard_missing");
            addCheck(checks, "sync_guard", "failed", "Plan does not include a sync strategy.", "sync_guard_missing");
            return;
        }
        var shadowOnly = boolValue(strategy.get("shadowOnly"));
        var enabled = boolValue(strategy.get("enabled"));
        if (!shadowOnly || enabled) {
            blockers.add("sync_guard_unsafe");
            addCheck(checks, "sync_guard", "failed", "Shadow Precheck requires shadowOnly=true and enabled=false.",
                "sync_guard_unsafe");
            return;
        }
        addCheck(checks, "sync_guard", "passed", "Sync strategy is guarded as shadow-only.");
    }

    private Map<String, String> fieldMappingSources(Map<String, Object> plan) {
        var mappings = new LinkedHashMap<String, String>();
        if (plan.get("fieldMappings") instanceof Map<?, ?> fields) {
            for (var entry : fields.entrySet()) {
                var sourceField = support.stringOrNull(entry.getKey());
                var standardField = support.stringOrNull(entry.getValue());
                if (sourceField != null && standardField != null) {
                    mappings.put(sourceField, standardField);
                }
            }
        }
        if (mappings.isEmpty() && plan.get("fieldMappingDetails") instanceof List<?> details) {
            for (var item : details) {
                if (!(item instanceof Map<?, ?> mapping)) {
                    continue;
                }
                var sourceField = support.stringOrNull(mapping.get("sourceField"));
                var standardField = support.stringOrNull(mapping.get("standardField"));
                if (sourceField != null && standardField != null) {
                    mappings.put(sourceField, standardField);
                }
            }
        }
        return mappings;
    }

    private Map<String, Object> standardEventPreview(Map<String, String> fieldMappings, Map<String, Object> sourceFields) {
        var preview = new LinkedHashMap<String, Object>();
        for (var entry : fieldMappings.entrySet()) {
            if (sourceFields.containsKey(entry.getKey())) {
                preview.put(entry.getValue(), sourceFields.get(entry.getKey()));
            }
        }
        return preview;
    }

    private void addCheck(List<Map<String, Object>> checks, String code, String result, String message) {
        addCheck(checks, code, result, message, (List<String>) null, null);
    }

    private void addCheck(List<Map<String, Object>> checks, String code, String result, String message, String blocker) {
        addCheck(checks, code, result, message, blocker == null ? null : List.of(blocker), null);
    }

    private void addCheck(
        List<Map<String, Object>> checks,
        String code,
        String result,
        String message,
        String blocker,
        Object details
    ) {
        addCheck(checks, code, result, message, blocker == null ? null : List.of(blocker), details);
    }

    private void addCheck(
        List<Map<String, Object>> checks,
        String code,
        String result,
        String message,
        Object details
    ) {
        addCheck(checks, code, result, message, (List<String>) null, details);
    }

    private void addCheck(
        List<Map<String, Object>> checks,
        String code,
        String result,
        String message,
        List<String> blockers,
        Object details
    ) {
        var check = new LinkedHashMap<String, Object>();
        check.put("code", code);
        check.put("result", result);
        check.put("message", message);
        if (blockers != null && !blockers.isEmpty()) {
            check.put("blockers", blockers);
        }
        if (details != null) {
            check.put("details", details);
        }
        checks.add(check);
    }

    private void addInferredEvidence(
        SourceField field,
        FieldProfile profile,
        Map<String, Object> fieldEvidence
    ) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("sourceField", field.fieldName());
        evidence.put("fieldType", field.fieldType());
        evidence.put("sampleValue", field.sampleValue());
        evidence.put("semanticType", profile.semanticType());
        evidence.put("standardField", profile.standardField());
        evidence.put("confidence", profile.confidence());
        evidence.put("source", "semantic_profile");
        evidence.put("reason", profile.reason());
        fieldEvidence.put(field.fieldName(), evidence);
    }

    private void addExistingMappingEvidence(
        SourceField field,
        FieldProfile profile,
        List<ExistingMapping> existingMappings,
        Map<String, Object> fieldEvidence
    ) {
        var standards = existingMappings.stream().map(ExistingMapping::standardField).toList();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("sourceField", field.fieldName());
        evidence.put("fieldType", field.fieldType());
        evidence.put("sampleValue", field.sampleValue());
        evidence.put("semanticType", support.stringOrDefault(field.semanticType(), profile.semanticType()));
        evidence.put("standardField", standards.get(0));
        evidence.put("confidence", field.confidenceOr(profile.confidence()));
        evidence.put("source", "existing_mapping");
        evidence.put("existingMapping", standards.get(0));
        evidence.put("existingMappings", standards);
        var transformRules = existingMappings.stream()
            .map(ExistingMapping::transformRule)
            .filter(rule -> rule != null && !rule.isBlank())
            .toList();
        if (!transformRules.isEmpty()) {
            evidence.put("transformRule", transformRules.get(0));
            evidence.put("transformRules", transformRules);
        }
        evidence.put("reason", "Existing field_mappings entry was preserved and used as evidence.");
        fieldEvidence.put(field.fieldName(), evidence);
    }

    private Map<String, Object> fieldMapping(String sourceField, String standardField, int confidence, String source) {
        return fieldMapping(sourceField, standardField, confidence, source, null);
    }

    private Map<String, Object> fieldMapping(
        String sourceField,
        String standardField,
        int confidence,
        String source,
        String transformRule
    ) {
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("sourceField", sourceField);
        mapping.put("standardField", standardField);
        mapping.put("confidence", clamp(confidence));
        mapping.put("source", source);
        if (transformRule != null && !transformRule.isBlank()) {
            mapping.put("transformRule", transformRule);
        }
        return mapping;
    }

    private String firstSourceForStandard(List<Map<String, Object>> fieldMappings, String standardField) {
        for (var mapping : fieldMappings) {
            if (standardField.equals(mapping.get("standardField"))) {
                return support.stringOrNull(mapping.get("sourceField"));
            }
        }
        return null;
    }

    private Map<String, Object> dedupStrategy(String idField, List<Map<String, Object>> fieldMappings) {
        if (idField != null) {
            var strategy = new LinkedHashMap<String, Object>();
            strategy.put("type", "external_id");
            strategy.put("fields", List.of(idField));
            strategy.put("fallback", "composite");
            return strategy;
        }
        var fields = new LinkedHashSet<String>();
        for (var standardField : List.of("eventType", "occurredAt", "actor", "assetRef", "subjectRef", "title")) {
            var sourceField = firstSourceForStandard(fieldMappings, standardField);
            if (sourceField != null) {
                fields.add(sourceField);
            }
        }
        var stable = fields.contains(firstSourceForStandard(fieldMappings, "occurredAt"))
            && (fields.contains(firstSourceForStandard(fieldMappings, "eventType"))
                || fields.contains(firstSourceForStandard(fieldMappings, "title")))
            && fields.size() >= 3;
        var strategy = new LinkedHashMap<String, Object>();
        strategy.put("type", "composite");
        strategy.put("fields", new ArrayList<>(fields));
        strategy.put("stable", stable);
        return strategy;
    }

    private Map<String, Object> templateMatch(TemplateMatcherService.TemplateMatch templateMatch) {
        var match = new LinkedHashMap<String, Object>();
        match.put("templateKey", templateMatch.templateKey());
        match.put("templateName", templateMatch.templateName());
        match.put("confidence", templateMatch.confidence());
        match.put("matchedBy", templateMatch.matchedBy());
        match.put("mainPlanCandidate", templateMatch.mainPlanCandidate());
        match.put("matchedSignals", templateMatch.matchedSignals());
        match.put("missingSignals", templateMatch.missingSignals());
        match.put("reason", templateMatch.reason());
        return match;
    }

    private Map<String, Object> fieldMappingObject(List<Map<String, Object>> fieldMappings) {
        var mapping = new LinkedHashMap<String, Object>();
        for (var item : fieldMappings) {
            var sourceField = support.stringOrNull(item.get("sourceField"));
            var standardField = support.stringOrNull(item.get("standardField"));
            if (sourceField != null && standardField != null) {
                mapping.put(sourceField, standardField);
            }
        }
        return mapping;
    }

    private Map<String, Object> syncStrategy(String cursorField) {
        var strategy = new LinkedHashMap<String, Object>();
        strategy.put("mode", cursorField == null ? "snapshot" : "incremental");
        strategy.put("cursorField", cursorField);
        strategy.put("shadowOnly", true);
        strategy.put("enabled", false);
        strategy.put("activation", "requires_status_shadow_ready");
        return strategy;
    }

    private int mappingConfidence(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        var total = 0;
        for (var value : values) {
            total += clamp(value);
        }
        return Math.round((float) total / values.size());
    }

    private int confidence(
        int templateConfidence,
        int mappingConfidence,
        int coverageConfidence,
        boolean missingOccurredAt,
        boolean dedupInsufficient
    ) {
        var score = Math.round(templateConfidence * 0.4f + mappingConfidence * 0.4f + coverageConfidence * 0.2f);
        if (missingOccurredAt) {
            score -= 30;
        }
        if (dedupInsufficient) {
            score -= 30;
        }
        return clamp(score);
    }

    private String recommendedAction(
        Coverage coverage,
        int mappingConfidence,
        int confidence,
        List<String> requiredFieldsMissing
    ) {
        if (coverage.limited()) {
            return "insufficient_coverage";
        }
        if (requiredFieldsMissing.contains("dedup_key_source_insufficient")) {
            return "needs_mapping";
        }
        if (mappingConfidence < 55) {
            return "needs_mapping";
        }
        if (!requiredFieldsMissing.isEmpty() || confidence < 75) {
            return "manual_review";
        }
        return "shadow_validate";
    }

    private Coverage coverage(ScanRun scanRun) {
        if (scanRun == null) {
            return new Coverage(70, false);
        }
        var limited = !"success".equalsIgnoreCase(scanRun.status())
            || scanRun.failedTables() > 0
            || scanRun.scannedTables() < scanRun.totalTables()
            || scanRun.scannedFields() < scanRun.totalFields();
        if (!limited) {
            return new Coverage(100, false);
        }
        var tableRatio = ratio(scanRun.scannedTables(), scanRun.totalTables());
        var fieldRatio = ratio(scanRun.scannedFields(), scanRun.totalFields());
        var confidence = clamp(Math.round(35 + ((tableRatio + fieldRatio) / 2.0f) * 45));
        return new Coverage(confidence, true);
    }

    private float ratio(int scanned, int total) {
        if (total <= 0) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, (float) scanned / total));
    }

    private ScanRun loadScanRun(Long dataSourceId, Long requestedScanRunId) {
        var rows = requestedScanRunId == null
            ? jdbcTemplate.queryForList("""
                select id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
                from schema_scan_runs
                where data_source_id = ?
                order by started_at desc, id desc
                limit 1
                """, dataSourceId)
            : jdbcTemplate.queryForList("""
                select id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
                from schema_scan_runs
                where data_source_id = ? and id = ?
                """, dataSourceId, requestedScanRunId);
        if (rows.isEmpty()) {
            if (requestedScanRunId != null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schema scan run not found: " + requestedScanRunId);
            }
            return null;
        }
        var row = rows.get(0);
        return new ScanRun(
            support.number(row.get("id")),
            support.stringOrDefault(row.get("status"), "unknown"),
            intValue(row.get("total_tables")),
            intValue(row.get("scanned_tables")),
            intValue(row.get("failed_tables")),
            intValue(row.get("total_fields")),
            intValue(row.get("scanned_fields"))
        );
    }

    private List<SourceTable> loadSourceTables(Long dataSourceId, Long scanRunId) {
        var rows = scanRunId == null
            ? jdbcTemplate.queryForList("""
                select id, data_source_id, scan_run_id, table_name, category
                from schema_tables
                where data_source_id = ?
                  and lower(coalesce(confirmation_status, 'confirmed')) in ('confirmed', 'accepted', 'auto_accepted')
                  and coalesce(lifecycle_status, 'active') = 'active'
                order by updated_at desc, id
                """, dataSourceId)
            : jdbcTemplate.queryForList("""
                select id, data_source_id, scan_run_id, table_name, category
                from schema_tables
                where data_source_id = ?
                  and scan_run_id = ?
                  and lower(coalesce(confirmation_status, 'confirmed')) in ('confirmed', 'accepted', 'auto_accepted')
                  and coalesce(lifecycle_status, 'active') = 'active'
                order by updated_at desc, id
                """, dataSourceId, scanRunId);
        var tables = new ArrayList<SourceTable>();
        for (var row : rows) {
            var tableId = support.number(row.get("id"));
            if (tableId == null) {
                continue;
            }
            var fields = loadFields(tableId);
            if (fields.isEmpty()) {
                continue;
            }
            tables.add(new SourceTable(
                tableId,
                support.number(row.get("scan_run_id")),
                support.stringOrDefault(row.get("table_name"), "unknown_table"),
                support.stringOrDefault(row.get("category"), ""),
                fields,
                loadMappings(tableId)
            ));
        }
        return tables;
    }

    private List<SourceField> loadFields(Long tableId) {
        return jdbcTemplate.queryForList("""
            select id, field_name, field_type, sample_value, semantic_type, confidence,
                   is_candidate_key, is_time_candidate, ordinal_position
            from schema_fields
            where schema_table_id = ?
              and coalesce(lifecycle_status, 'active') = 'active'
            order by ordinal_position nulls last, id
            """, tableId).stream()
            .map(row -> new SourceField(
                support.number(row.get("id")),
                support.stringOrDefault(row.get("field_name"), "field"),
                support.stringOrDefault(row.get("field_type"), "text"),
                support.stringOrNull(row.get("sample_value")),
                support.stringOrNull(row.get("semantic_type")),
                intValueOrNull(row.get("confidence")),
                row
            ))
            .toList();
    }

    private Map<String, List<ExistingMapping>> loadMappings(Long tableId) {
        var mappings = new LinkedHashMap<String, List<ExistingMapping>>();
        var rows = jdbcTemplate.queryForList("""
            select source_field, standard_field, transform_rule
            from field_mappings
            where schema_table_id = ?
            order by id
            """, tableId);
        for (var row : rows) {
            var sourceField = support.stringOrNull(row.get("source_field"));
            var standardField = support.stringOrNull(row.get("standard_field"));
            if (sourceField == null || standardField == null) {
                continue;
            }
            mappings.computeIfAbsent(sourceField, ignored -> new ArrayList<>())
                .add(new ExistingMapping(standardField, support.stringOrNull(row.get("transform_rule"))));
        }
        return mappings;
    }

    private Long insertPlan(Long dataSourceId, Long scanRunId, String name, String status, Map<String, Object> plan) {
        var json = toJson(plan);
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
                values (?, ?, ?, ?, cast(? as jsonb))
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, dataSourceId);
            statement.setObject(2, scanRunId);
            statement.setString(3, name);
            statement.setString(4, status);
            statement.setString(5, json);
            return statement;
        }, keyHolder);
        var keys = keyHolder.getKeys();
        Number key = null;
        if (keys != null && keys.get("id") instanceof Number id) {
            key = id;
        } else if (keys != null && keys.get("ID") instanceof Number id) {
            key = id;
        } else {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("Insert did not return a generated id");
        }
        return key.longValue();
    }

    private Long upsertPlan(Long dataSourceId, Long scanRunId, String name, String status, Map<String, Object> plan) {
        var existing = matchingPlan(dataSourceId, plan, Set.of("suggested", "review_required"));
        if (existing != null) {
            jdbcTemplate.update("""
                update ingestion_plans
                set scan_run_id = ?, name = ?, status = ?, plan_json = cast(? as jsonb), updated_at = now()
                where id = ?
                """, scanRunId, name, status, toJson(plan), existing.get("id"));
            return ((Number) existing.get("id")).longValue();
        }

        var approved = matchingPlan(dataSourceId, plan, Set.of("approved", "shadow_ready"));
        if (approved != null) {
            @SuppressWarnings("unchecked")
            var risks = (List<String>) plan.get("risks");
            if (!risks.contains("existing_approved_plan")) {
                risks.add("existing_approved_plan");
            }
            status = "review_required";
        }
        return insertPlan(dataSourceId, scanRunId, name, status, plan);
    }

    private Map<String, Object> planRow(Long planId) {
        return planRow(jdbcTemplate.queryForMap("""
            select id, data_source_id, scan_run_id, name, status, plan_json, created_at, updated_at
            from ingestion_plans
            where id = ?
            """, planId));
    }

    private List<Map<String, Object>> planRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::planRow).toList();
    }

    private Map<String, Object> planRow(Map<String, Object> row) {
        var normalized = new LinkedHashMap<String, Object>(row);
        normalized.put("plan_json", parsePlan(row.get("plan_json")));
        return normalized;
    }

    private Map<String, Object> matchingPlan(
        Long dataSourceId,
        Map<String, Object> plan,
        Set<String> statuses
    ) {
        var rows = jdbcTemplate.queryForList("""
            select id, status, plan_json
            from ingestion_plans
            where data_source_id = ?
            order by id
            """, dataSourceId);
        for (var row : rows) {
            if (!statuses.contains(support.stringOrDefault(row.get("status"), ""))) {
                continue;
            }
            if (samePlan(parsePlan(row.get("plan_json")), plan)) {
                return row;
            }
        }
        return null;
    }

    private boolean samePlan(Map<String, Object> existing, Map<String, Object> current) {
        return String.valueOf(existing.get("schemaTableId")).equals(String.valueOf(current.get("schemaTableId")))
            && String.valueOf(planMode(existing)).equals(String.valueOf(planMode(current)))
            && String.valueOf(templateKey(existing)).equals(String.valueOf(templateKey(current)));
    }

    private Object planMode(Map<String, Object> plan) {
        var mode = plan.get("mode");
        var templateKey = templateKey(plan);
        if (mode == null || String.valueOf(mode).isBlank() || String.valueOf(mode).equals(String.valueOf(templateKey))) {
            return INGESTION_MODE;
        }
        return mode;
    }

    @SuppressWarnings("unchecked")
    private Object templateKey(Map<String, Object> plan) {
        if (plan.get("templateMatch") instanceof Map<?, ?> template) {
            return template.get("templateKey");
        }
        return null;
    }

    private Map<String, Object> parsePlan(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        try {
            var node = value instanceof byte[] bytes
                ? objectMapper.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                : objectMapper.readTree(String.valueOf(value));
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize ingestion plan", ex);
        }
    }

    private void ensureDataSourceExists(Long dataSourceId) {
        var count = jdbcTemplate.queryForObject(
            "select count(*) from data_sources where id = ?",
            Long.class,
            dataSourceId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Data source not found: " + dataSourceId);
        }
    }

    private void validateStatus(String status) {
        if (status == null || !STATUS_WHITELIST.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ingestion plan status: " + status);
        }
    }

    private String normalizeStatusOrNull(String status) {
        var normalized = support.blankToNull(status);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        var text = support.stringOrNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(support::stringOrNull)
                .filter(item -> item != null)
                .toList();
        }
        var item = support.stringOrNull(value);
        return item == null ? List.of() : List.of(item);
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(support.stringOrDefault(value, "false"));
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Integer intValueOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record ScanRun(
        Long id,
        String status,
        int totalTables,
        int scannedTables,
        int failedTables,
        int totalFields,
        int scannedFields
    ) {
    }

    private record Coverage(int confidence, boolean limited) {
    }

    private record ExistingMapping(String standardField, String transformRule) {
    }

    private record SourceTable(
        Long id,
        Long scanRunId,
        String tableName,
        String category,
        List<SourceField> fields,
        Map<String, List<ExistingMapping>> existingMappings
    ) {
        private List<String> fieldNames() {
            return fields.stream().map(SourceField::fieldName).toList();
        }
    }

    private record SourceField(
        Long id,
        String fieldName,
        String fieldType,
        String sampleValue,
        String semanticType,
        Integer confidence,
        Map<String, Object> row
    ) {
        private int confidenceOr(int fallback) {
            return confidence == null ? fallback : confidence;
        }
    }
}
