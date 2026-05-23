import type { IngestionPlanRow } from '../../../types';

export interface NormalizedPlanMapping {
  key: string;
  sourceField: string;
  standardField: string;
  confidence?: number;
  transformRule?: string;
  reason?: string;
}

export interface NormalizedSignalEvidence {
  key: string;
  signal: string;
  sourceFields: string[];
  source: string;
}

export interface NormalizedIngestionPlan {
  id: number;
  name: string;
  status: string;
  dataSourceName: string;
  candidateTable: string;
  templateType: string;
  overallConfidence?: number;
  templateConfidence?: number;
  coverageConfidence?: number;
  mappingCompleteness?: number;
  fieldMappings: NormalizedPlanMapping[];
  dedupFields: string[];
  dedupRule: string;
  dedupWindow: string;
  missingFields: string[];
  risks: string[];
  recommendedActions: string[];
  reasons: string[];
  signalEvidence: NormalizedSignalEvidence[];
  matchedSignals: string[];
  missingSignals: string[];
  generationVersion: string;
  generatedAt?: string | number;
}

function parsePlanJson(value: unknown): Record<string, unknown> {
  if (!value) {
    return {};
  }
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value) as unknown;
      return parsePlanJson(parsed);
    } catch {
      return {};
    }
  }
  if (typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return {};
}

function firstDefined(records: Array<Record<string, unknown>>, keys: string[]) {
  for (const record of records) {
    for (const key of keys) {
      const value = record[key];
      if (value !== undefined && value !== null && value !== '') {
        return value;
      }
    }
  }
  return undefined;
}

function toNumber(value: unknown) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string' && value.trim()) {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : undefined;
  }
  return undefined;
}

function toText(value: unknown) {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (typeof value === 'object' && !Array.isArray(value)) {
    const record = value as Record<string, unknown>;
    return toText(firstDefined([record], [
      'name',
      'fieldName',
      'field_name',
      'field',
      'standardField',
      'standard_field',
      'message',
      'description',
      'reason',
      'action',
    ]));
  }
  return '';
}

function toTextArray(value: unknown) {
  if (!value) {
    return [];
  }
  if (Array.isArray(value)) {
    return value.map(toText).filter(Boolean);
  }
  const text = toText(value);
  return text ? [text] : [];
}

function normalizeFieldEvidence(value: unknown, rowId: number) {
  if (!value) {
    return [];
  }
  if (Array.isArray(value)) {
    return value.map((item, index) => {
      const evidence = parsePlanJson(item);
      return {
        key: `${rowId}-evidence-${index}`,
        sourceField: toText(firstDefined([evidence], ['sourceField', 'source_field', 'source', 'fieldName', 'field_name', 'field', 'from'])),
        standardField: toText(firstDefined([evidence], ['standardField', 'standard_field', 'target', 'targetField', 'target_field', 'to'])),
        reason: toText(firstDefined([evidence], ['reason', 'description', 'message', 'explanation'])),
      };
    }).filter((mapping) => mapping.sourceField || mapping.standardField || mapping.reason);
  }
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).map(([fieldKey, rawEvidence]) => {
      const evidence = parsePlanJson(rawEvidence);
      return {
        key: `${rowId}-evidence-${fieldKey}`,
        sourceField: toText(firstDefined([evidence], ['sourceField', 'source_field', 'source', 'fieldName', 'field_name', 'field', 'from'])) || fieldKey,
        standardField: toText(firstDefined([evidence], ['standardField', 'standard_field', 'target', 'targetField', 'target_field', 'to'])),
        reason: toText(firstDefined([evidence], ['reason', 'description', 'message', 'explanation'])) || toText(rawEvidence),
      };
    }).filter((mapping) => mapping.sourceField || mapping.standardField || mapping.reason);
  }
  return [];
}

function normalizeSignalEvidenceItem(value: unknown, key: string, signalFallback = ''): NormalizedSignalEvidence {
  const evidence = parsePlanJson(value);
  const normalized: NormalizedSignalEvidence = {
    key,
    signal: toText(firstDefined([evidence], ['signal', 'name', 'key'])) || signalFallback,
    sourceFields: toTextArray(firstDefined([evidence], ['sourceFields', 'source_fields', 'fields', 'sourceField', 'source_field', 'fieldName', 'field_name', 'field'])),
    source: toText(firstDefined([evidence], ['source', 'matchedBy', 'matched_by', 'origin'])),
  };
  return normalized;
}

function normalizeSignalEvidence(value: unknown, rowId: number): NormalizedSignalEvidence[] {
  if (!value) {
    return [];
  }
  if (Array.isArray(value)) {
    return value.map((item, index) => normalizeSignalEvidenceItem(item, `${rowId}-signal-${index}`))
      .filter((evidence) => evidence.signal || evidence.sourceFields.length || evidence.source);
  }
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([signalKey, rawEvidence]) => normalizeSignalEvidenceItem(rawEvidence, `${rowId}-signal-${signalKey}`, signalKey))
      .filter((evidence) => evidence.signal || evidence.sourceFields.length || evidence.source);
  }
  return [];
}

export function normalizePlan(row: IngestionPlanRow): NormalizedIngestionPlan {
  const rowRecord = row as unknown as Record<string, unknown>;
  const planRecord = parsePlanJson(row.plan_json ?? row.planJson);
  const templateRecord = parsePlanJson(firstDefined([planRecord], ['templateMatch', 'template_match']));
  const records = [rowRecord, planRecord];
  const rawMappings = firstDefined([planRecord], ['fieldMappingDetails', 'field_mapping_details', 'fieldMappings', 'field_mappings', 'mappings']);
  const fieldEvidence = normalizeFieldEvidence(firstDefined([planRecord], ['fieldEvidence', 'field_evidence']), row.id);
  const signalEvidence = normalizeSignalEvidence(firstDefined([templateRecord], ['signalEvidence', 'signal_evidence']), row.id);
  const matchedSignals = toTextArray(firstDefined([templateRecord], ['matchedSignals', 'matched_signals']));
  const missingSignals = toTextArray(firstDefined([templateRecord], ['missingSignals', 'missing_signals']));
  const findEvidence = (sourceField: string, standardField: string) => fieldEvidence.find((evidence) =>
    (sourceField && evidence.sourceField === sourceField)
    || (standardField && evidence.standardField === standardField)
    || (sourceField && evidence.standardField === sourceField)
  );
  const fieldMappings = Array.isArray(rawMappings)
    ? rawMappings.map((item, index) => {
      const mapping = parsePlanJson(item);
      const sourceField = toText(firstDefined([mapping], ['sourceField', 'source_field', 'source', 'fieldName', 'field_name', 'field', 'from']));
      const standardField = toText(firstDefined([mapping], ['standardField', 'standard_field', 'target', 'targetField', 'target_field', 'to']));
      const evidence = findEvidence(sourceField, standardField);
      return {
        key: `${row.id}-${index}`,
        sourceField: sourceField || evidence?.sourceField || '',
        standardField: standardField || evidence?.standardField || '',
        confidence: toNumber(firstDefined([mapping], ['confidence', 'mappingConfidence', 'mapping_confidence'])),
        transformRule: toText(firstDefined([mapping], ['transformRule', 'transform_rule', 'rule'])),
        reason: toText(firstDefined([mapping], ['reason', 'description'])) || evidence?.reason,
      };
    }).filter((mapping) => mapping.sourceField || mapping.standardField)
    : (rawMappings && typeof rawMappings === 'object'
      ? Object.entries(rawMappings as Record<string, unknown>).map(([sourceField, standardField]) => {
        const standardFieldText = toText(standardField);
        const evidence = findEvidence(sourceField, standardFieldText);
        return {
          key: `${row.id}-${sourceField}`,
          sourceField: evidence?.sourceField || sourceField,
          standardField: evidence?.standardField || standardFieldText,
          reason: evidence?.reason,
        };
      }).filter((mapping) => mapping.sourceField || mapping.standardField)
      : fieldEvidence);
  const dedupRecord = parsePlanJson(firstDefined([planRecord], ['dedupStrategy', 'dedup_strategy', 'dedup']));
  const reasonText = toText(firstDefined([planRecord], ['reason', 'explanation']));

  return {
    id: row.id,
    name: toText(firstDefined(records, ['name'])) || `推荐方案 #${row.id}`,
    status: toText(firstDefined(records, ['status'])) || 'draft',
    dataSourceName: toText(firstDefined(records, ['data_source_name', 'dataSourceName'])) || '-',
    candidateTable: toText(firstDefined(records, ['candidate_table', 'candidateTable', 'mainTable', 'main_table', 'table_name', 'tableName'])) || '-',
    templateType: toText(firstDefined([templateRecord, rowRecord, planRecord], ['templateKey', 'template_key', 'templateName', 'template_name', 'template_type', 'templateType', 'template']))
      || toText(firstDefined([planRecord], ['mode']))
      || '-',
    overallConfidence: toNumber(firstDefined(records, ['overall_confidence', 'overallConfidence', 'confidence'])),
    templateConfidence: toNumber(firstDefined([rowRecord, planRecord, templateRecord], ['template_confidence', 'templateConfidence', 'confidence'])),
    coverageConfidence: toNumber(firstDefined(records, ['coverage_confidence', 'coverageConfidence'])),
    mappingCompleteness: toNumber(firstDefined(records, ['mapping_completeness', 'mappingCompleteness', 'mappingConfidence', 'mapping_confidence'])),
    fieldMappings,
    dedupFields: toTextArray(firstDefined([dedupRecord], ['fields', 'sourceFields', 'source_fields'])),
    dedupRule: toText(firstDefined([dedupRecord], ['rule', 'description'])),
    dedupWindow: toText(firstDefined([dedupRecord], ['window', 'timeWindow', 'time_window'])),
    missingFields: toTextArray(firstDefined([planRecord], ['requiredFieldsMissing', 'required_fields_missing', 'missingFields', 'missing_fields'])),
    risks: toTextArray(firstDefined([planRecord], ['riskTips', 'risk_tips', 'risks', 'warnings'])),
    recommendedActions: toTextArray(firstDefined([planRecord], ['recommendedAction', 'recommended_action', 'recommendedActions', 'recommended_actions', 'actions'])),
    reasons: [
      ...toTextArray(firstDefined([templateRecord], ['reason'])),
      ...toTextArray(firstDefined([planRecord], ['reasons', 'evidence'])),
      ...fieldEvidence.map((evidence) => [evidence.sourceField, evidence.standardField, evidence.reason].filter(Boolean).join('：')).filter(Boolean),
      ...(reasonText ? [reasonText] : []),
    ],
    signalEvidence,
    matchedSignals,
    missingSignals,
    generationVersion: toText(firstDefined(records, ['generation_version', 'generationVersion', 'version'])) || '-',
    generatedAt: firstDefined(records, ['generated_at', 'generatedAt', 'created_at', 'createdAt', 'updated_at', 'updatedAt']) as string | number | undefined,
  };
}
