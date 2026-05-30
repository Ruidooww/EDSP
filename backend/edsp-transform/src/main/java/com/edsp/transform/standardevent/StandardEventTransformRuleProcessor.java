package com.edsp.transform.standardevent;

import com.edsp.transform.standardevent.dedup.DedupKeyBuilder;
import com.edsp.transform.standardevent.normalize.RiskScoreCalculator;
import com.edsp.transform.standardevent.normalize.SeverityNormalizer;
import com.edsp.transform.standardevent.normalize.TimeValueParser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class StandardEventTransformRuleProcessor {
    private final TimeValueParser timeValueParser;
    private final SeverityNormalizer severityNormalizer;
    private final RiskScoreCalculator riskScoreCalculator;
    private final DedupKeyBuilder dedupKeyBuilder;
    private final TransformRuleApplier transformRuleApplier;

    StandardEventTransformRuleProcessor() {
        this(new TimeValueParser(), new SeverityNormalizer(), new RiskScoreCalculator(), new DedupKeyBuilder());
    }

    StandardEventTransformRuleProcessor(
        TimeValueParser timeValueParser,
        SeverityNormalizer severityNormalizer,
        RiskScoreCalculator riskScoreCalculator,
        DedupKeyBuilder dedupKeyBuilder
    ) {
        this(timeValueParser, severityNormalizer, riskScoreCalculator, dedupKeyBuilder, new TransformRuleApplier());
    }

    StandardEventTransformRuleProcessor(
        TimeValueParser timeValueParser,
        SeverityNormalizer severityNormalizer,
        RiskScoreCalculator riskScoreCalculator,
        DedupKeyBuilder dedupKeyBuilder,
        TransformRuleApplier transformRuleApplier
    ) {
        this.timeValueParser = timeValueParser;
        this.severityNormalizer = severityNormalizer;
        this.riskScoreCalculator = riskScoreCalculator;
        this.dedupKeyBuilder = dedupKeyBuilder;
        this.transformRuleApplier = transformRuleApplier == null ? new TransformRuleApplier() : transformRuleApplier;
    }

    TransformResult process(SourceRow row, MappingPlan plan, TransformOptions options) {
        var sourceRow = row == null ? new SourceRow(null) : row;
        var mappingPlan = plan == null ? new MappingPlan(null, null) : plan;
        var transformOptions = options == null ? new TransformOptions(null, null, null, null) : options;
        var warnings = new ArrayList<String>();
        var values = mappedValues(sourceRow, mappingPlan, warnings);
        var errors = new ArrayList<String>();
        var occurredAt = parseRequiredTime(values.get("occurredAt"), errors);
        var severity = normalizeSeverity(values.get("severity"), errors);
        var sourceSystem = sourceSystem(transformOptions.dataSourceId(), transformOptions.schemaTableId());
        var externalId = stringOrNull(values.get("externalId"));
        var eventType = stringOrDefault(first(values.get("eventType"), values.get("title")), "ingestion_plan_event");
        var actor = stringOrNull(values.get("actor"));
        var assetRef = stringOrNull(values.get("assetRef"));
        var subjectRef = stringOrNull(values.get("subjectRef"));
        var dedupKey = dedupKeyBuilder.build(
            transformOptions.dataSourceId(),
            transformOptions.schemaTableId(),
            transformOptions.sourceTable(),
            sourceRow.values(),
            mappingPlan.dedupFields(),
            sourceSystem,
            externalId,
            eventType,
            occurredAt,
            actor,
            assetRef,
            subjectRef
        );
        if (dedupKey == null) {
            errors.add("dedup_key_missing");
        }
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("sourceTable", transformOptions.sourceTable());
        normalized.put("mapped", values);
        var extra = new LinkedHashMap<String, Object>();
        extra.put("syncMode", transformOptions.syncMode());
        extra.put("sourceTable", transformOptions.sourceTable());
        extra.put("dataSourceId", transformOptions.dataSourceId());
        var draft = new StandardEventDraft(
            sourceSystem,
            externalId,
            eventType,
            occurredAt,
            actor,
            assetRef,
            stringOrDefault(values.get("subjectType"), "event"),
            subjectRef == null ? assetRef : subjectRef,
            stringOrNull(values.get("action")),
            stringOrDefault(values.get("result"), "detected"),
            severity,
            riskScoreCalculator.riskScore(severity),
            dedupKey,
            normalized,
            extra
        );
        return new TransformResult(draft, errors, warnings);
    }

    private LinkedHashMap<String, Object> mappedValues(SourceRow row, MappingPlan plan, List<String> warnings) {
        var values = new LinkedHashMap<String, Object>();
        plan.fieldMappings().forEach((sourceField, standardField) -> {
            var value = row.values().get(sourceField);
            var detail = exactDetail(plan, sourceField, standardField);
            if (detail != null) {
                var application = transformRuleApplier.apply(
                    value,
                    detail.transformRule(),
                    sourceField,
                    detail.transformRulePayload()
                );
                warnings.addAll(application.warnings());
                value = application.value();
            }
            values.put(standardField, value);
        });
        return values;
    }

    private MappingPlan.FieldMappingDetail exactDetail(MappingPlan plan, String sourceField, String standardField) {
        return plan.fieldMappingDetails().stream()
            .filter(detail -> Objects.equals(sourceField, detail.sourceField())
                && Objects.equals(standardField, detail.standardField()))
            .findFirst()
            .orElse(null);
    }

    private OffsetDateTime parseRequiredTime(Object value, List<String> errors) {
        var text = stringOrNull(value);
        if (text == null) {
            errors.add("missing_occurred_at");
            return null;
        }
        try {
            return timeValueParser.parse(text);
        } catch (RuntimeException ex) {
            errors.add("invalid_time_format");
            return null;
        }
    }

    private String normalizeSeverity(Object value, List<String> errors) {
        var text = stringOrNull(value);
        if (text == null) {
            return "info";
        }
        var severity = severityNormalizer.normalize(text);
        if (severity == null) {
            errors.add("severity_unrecognized");
            return "info";
        }
        return severity;
    }

    private String sourceSystem(Long dataSourceId, Long schemaTableId) {
        if (dataSourceId == null) {
            return "external";
        }
        return "ds:%d:st:%s".formatted(dataSourceId, schemaTableId);
    }

    private Object first(Object... values) {
        for (var value : values) {
            if (stringOrNull(value) != null) {
                return value;
            }
        }
        return null;
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
}
