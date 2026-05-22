import {
  BranchesOutlined,
  CheckCircleOutlined,
  CloudSyncOutlined,
  DatabaseOutlined,
  FileSearchOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  TableOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, Modal, Select, Space, Statistic, Steps, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ReactNode } from 'react';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost, apiPut } from '../api';
import type {
  DataSourceRow,
  IngestionPlanRow,
  IngestionPlanShadowValidationReport,
  SchemaChangeEventRow,
  SchemaFieldRow,
  SchemaScanRunRow,
  SchemaTableRow,
} from '../types';

interface SchemaMappingRow {
  id: number;
  source_field: string;
  standard_field: string;
  transform_rule?: string;
}

interface MetadataCollectValues {
  dataSourceId: number;
  collectMode: 'auto_scan' | 'sample_json' | 'file_sample' | 'manual_patch';
  tableName: string;
  category: string;
  samplePayload?: string;
}

interface SuggestedField {
  fieldName: string;
  fieldType: string;
  nullable: boolean;
  sampleValue: string;
  description: string;
  standardField?: string;
  transformRule?: string;
}

const COLLECT_MODE_OPTIONS = [
  { value: 'auto_scan', label: '自动扫描数据源结构' },
  { value: 'sample_json', label: '解析样例 JSON' },
  { value: 'file_sample', label: '导入 CSV / Excel 样例' },
  { value: 'manual_patch', label: '手工补录少量字段' },
];

const FIELD_PRESETS: Record<MetadataCollectValues['collectMode'], SuggestedField[]> = {
  auto_scan: [
    { fieldName: 'event_id', fieldType: 'varchar', nullable: false, sampleValue: 'EVT-20260520-001', description: '外部事件唯一编号', standardField: 'externalId', transformRule: '直接映射' },
    { fieldName: 'event_name', fieldType: 'varchar', nullable: false, sampleValue: '疑似敏感文件外发', description: '告警标题', standardField: 'title', transformRule: '直接映射' },
    { fieldName: 'risk_level', fieldType: 'varchar', nullable: false, sampleValue: 'high', description: '风险等级', standardField: 'severity', transformRule: '等级标准化' },
    { fieldName: 'event_time', fieldType: 'timestamp', nullable: false, sampleValue: '2026-05-20 09:42:00', description: '事件发生时间', standardField: 'occurredAt', transformRule: '时间格式转换' },
    { fieldName: 'user_name', fieldType: 'varchar', nullable: true, sampleValue: '张三', description: '涉及账号', standardField: 'actor', transformRule: '直接映射' },
    { fieldName: 'asset_ip', fieldType: 'varchar', nullable: true, sampleValue: '10.8.12.25', description: '终端 IP', standardField: 'assetRef', transformRule: '资产字段拼接' },
    { fieldName: 'phone', fieldType: 'varchar', nullable: true, sampleValue: '138****8821', description: '敏感字段候选', standardField: 'detail.phone', transformRule: '脱敏后写入详情' },
  ],
  sample_json: [
    { fieldName: 'id', fieldType: 'string', nullable: false, sampleValue: 'DLP-20260520-008', description: '接口事件编号', standardField: 'externalId', transformRule: '直接映射' },
    { fieldName: 'alertName', fieldType: 'string', nullable: false, sampleValue: '邮件外发包含敏感附件', description: '接口告警名称', standardField: 'title', transformRule: '直接映射' },
    { fieldName: 'level', fieldType: 'string', nullable: false, sampleValue: 'medium', description: '接口风险等级', standardField: 'severity', transformRule: '等级标准化' },
    { fieldName: 'operator', fieldType: 'string', nullable: true, sampleValue: 'lisi', description: '操作账号', standardField: 'actor', transformRule: '直接映射' },
    { fieldName: 'payload', fieldType: 'json', nullable: true, sampleValue: '{}', description: '原始扩展内容', standardField: 'detail.raw', transformRule: '保留原始 JSON' },
  ],
  file_sample: [
    { fieldName: 'source_file', fieldType: 'varchar', nullable: false, sampleValue: '2026-05-audit.csv', description: '来源文件', standardField: 'detail.sourceFile', transformRule: '写入详情' },
    { fieldName: 'row_hash', fieldType: 'varchar', nullable: false, sampleValue: 'b3c7e9', description: '行指纹', standardField: 'externalId', transformRule: '作为去重键' },
    { fieldName: 'event_time', fieldType: 'datetime', nullable: true, sampleValue: '2026-05-20 10:00:00', description: '事件时间', standardField: 'occurredAt', transformRule: '时间格式转换' },
    { fieldName: 'raw_payload', fieldType: 'text', nullable: true, sampleValue: '{}', description: '原始内容', standardField: 'detail.raw', transformRule: '保留原始行' },
  ],
  manual_patch: [
    { fieldName: 'external_id', fieldType: 'varchar', nullable: false, sampleValue: 'MANUAL-001', description: '外部编号', standardField: 'externalId', transformRule: '直接映射' },
    { fieldName: 'title', fieldType: 'varchar', nullable: false, sampleValue: '手工补录告警', description: '告警标题', standardField: 'title', transformRule: '直接映射' },
    { fieldName: 'created_time', fieldType: 'timestamp', nullable: true, sampleValue: '2026-05-20 10:00:00', description: '创建时间', standardField: 'occurredAt', transformRule: '时间格式转换' },
  ],
};

const STANDARD_FIELD_LABELS: Record<string, string> = {
  externalId: '外部告警 ID',
  title: '告警标题',
  severity: '风险等级',
  occurredAt: '发生时间',
  actor: '账号 / 操作人',
  assetRef: '资产 / 终端',
  alertType: '事件类型',
  'detail.phone': '敏感手机号',
  'detail.raw': '原始内容',
  'detail.sourceFile': '来源文件',
};

function statusTag(value?: string) {
  if (value === 'confirmed') {
    return <Tag color="success">已确认</Tag>;
  }
  if (value === 'ignored') {
    return <Tag>已忽略</Tag>;
  }
  return <Tag color="warning">待确认</Tag>;
}

function riskTag(fieldName: string, description?: string) {
  const name = fieldName.toLowerCase();
  if (/phone|mobile|id_card|email|bank|address|customer|cert/.test(name) || description?.includes('敏感')) {
    return <Tag color="red">敏感候选</Tag>;
  }
  if (/time|date|occur|created/.test(name)) {
    return <Tag color="blue">时间字段</Tag>;
  }
  if (/user|account|operator|actor|employee/.test(name)) {
    return <Tag color="purple">账号字段</Tag>;
  }
  if (/level|severity|risk/.test(name)) {
    return <Tag color="orange">等级字段</Tag>;
  }
  if (/event|alert|title|name/.test(name)) {
    return <Tag color="cyan">事件字段</Tag>;
  }
  return <Tag>普通字段</Tag>;
}

function recommendStandardField(fieldName: string) {
  const name = fieldName.toLowerCase();
  if (/event_id|incident_no|row_hash|external_id|^id$/.test(name)) return 'externalId';
  if (/event_name|alert_name|title|name/.test(name)) return 'title';
  if (/level|severity|risk_level/.test(name)) return 'severity';
  if (/time|date|occur|created/.test(name)) return 'occurredAt';
  if (/user|account|operator|actor|employee|sender/.test(name)) return 'actor';
  if (/asset|host|ip|device/.test(name)) return 'assetRef';
  if (/type|behavior|operation/.test(name)) return 'alertType';
  if (/phone|mobile/.test(name)) return 'detail.phone';
  if (/payload|raw/.test(name)) return 'detail.raw';
  return '';
}

function defaultTableName() {
  const date = new Date();
  const suffix = `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}`;
  return `security_alert_event_${suffix}`;
}

function formatTime(value?: string | number) {
  if (!value) {
    return '-';
  }
  const normalizedValue = typeof value === 'number' && value < 100000000000 ? value * 1000 : value;
  const date = new Date(normalizedValue);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function scanStatusTag(value?: string) {
  if (value === 'success') {
    return <Tag color="success">成功</Tag>;
  }
  if (value === 'running') {
    return <Tag color="processing">运行中</Tag>;
  }
  if (value === 'failed') {
    return <Tag color="error">失败</Tag>;
  }
  return <Tag>{value || '未开始'}</Tag>;
}

function changeTypeTag(value?: string) {
  const labels: Record<string, string> = {
    added: '新增',
    removed: '删除',
    type_changed: '类型变化',
    nullability_changed: '约束变化',
    reappeared: '重新出现',
  };
  const color = value === 'removed' || value === 'type_changed'
    ? 'red'
    : value === 'added'
      ? 'green'
      : 'blue';
  return <Tag color={color}>{labels[value || ''] || value || '-'}</Tag>;
}

function changeStatusTag(value?: string) {
  if (value === 'pending') {
    return <Tag color="warning">待确认</Tag>;
  }
  if (value === 'auto_accepted') {
    return <Tag color="success">自动处理</Tag>;
  }
  if (value === 'accepted') {
    return <Tag color="success">已确认</Tag>;
  }
  if (value === 'ignored') {
    return <Tag>已忽略</Tag>;
  }
  return <Tag>{value || '-'}</Tag>;
}

function severityTag(value?: string) {
  if (value === 'high' || value === 'critical') {
    return <Tag color="red">高</Tag>;
  }
  if (value === 'medium') {
    return <Tag color="orange">中</Tag>;
  }
  if (value === 'low') {
    return <Tag color="blue">低</Tag>;
  }
  return <Tag>提示</Tag>;
}

interface NormalizedPlanMapping {
  key: string;
  sourceField: string;
  standardField: string;
  confidence?: number;
  transformRule?: string;
  reason?: string;
}

interface NormalizedIngestionPlan {
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
  generationVersion: string;
  generatedAt?: string | number;
}

const PLAN_STATUS_FILTER_OPTIONS = [
  { value: 'suggested', label: '已推荐' },
  { value: 'review_required', label: '待复核' },
  { value: 'approved', label: '已批准' },
  { value: 'shadow_ready', label: '试运行准备' },
  { value: 'rejected', label: '已拒绝' },
];
const PLAN_STATUS_FILTER_VALUES = new Set(PLAN_STATUS_FILTER_OPTIONS.map((option) => option.value));

const PLAN_STATUS_LABELS: Record<string, string> = {
  draft: '草稿',
  generated: '已生成',
  suggested: '已推荐',
  review: '待复核',
  review_required: '待复核',
  approved: '已批准',
  shadow_ready: '试运行准备',
  rejected: '已拒绝',
};

const PLAN_STATUS_COLORS: Record<string, string> = {
  draft: 'default',
  generated: 'processing',
  suggested: 'processing',
  review: 'warning',
  review_required: 'warning',
  approved: 'success',
  shadow_ready: 'cyan',
  rejected: 'error',
};

const SHADOW_VALIDATION_RESULT_LABELS: Record<string, string> = {
  passed: '校验通过',
  warning: '存在提醒',
  blocked: '存在阻断',
  failed: '未通过',
};

const SHADOW_VALIDATION_RESULT_COLORS: Record<string, string> = {
  passed: 'success',
  warning: 'warning',
  blocked: 'error',
  failed: 'error',
};

function planStatusTag(value?: string) {
  const status = value || 'draft';
  return <Tag color={PLAN_STATUS_COLORS[status] || 'default'} style={{ whiteSpace: 'normal', wordBreak: 'break-word' }}>{PLAN_STATUS_LABELS[status] || status}</Tag>;
}

function shadowValidationResultTag(value?: string) {
  const result = value || 'unknown';
  return (
    <Tag color={SHADOW_VALIDATION_RESULT_COLORS[result] || 'default'} style={{ whiteSpace: 'normal', wordBreak: 'break-word' }}>
      {SHADOW_VALIDATION_RESULT_LABELS[result] || result}
    </Tag>
  );
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
    return toText(firstDefined([record], ['name', 'fieldName', 'field_name', 'field', 'standardField', 'standard_field', 'message', 'description', 'reason', 'action']));
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

function formatUnknownValue(value: unknown): string {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  if (Array.isArray(value)) {
    return value.map(formatUnknownValue).filter(Boolean).join(' / ');
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return toText(value);
    }
  }
  return toText(value);
}

function renderWrappedTag(content: ReactNode, color?: string) {
  return (
    <Tag color={color} style={{ whiteSpace: 'normal', wordBreak: 'break-word', maxWidth: '100%' }}>
      {content}
    </Tag>
  );
}

function formatConfidence(value?: number) {
  if (value === undefined) {
    return '-';
  }
  const percent = value <= 1 ? value * 100 : value;
  return `${Math.round(percent)}%`;
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

function normalizePlan(row: IngestionPlanRow): NormalizedIngestionPlan {
  const rowRecord = row as unknown as Record<string, unknown>;
  const planRecord = parsePlanJson(row.plan_json ?? row.planJson);
  const templateRecord = parsePlanJson(firstDefined([planRecord], ['templateMatch', 'template_match']));
  const records = [rowRecord, planRecord];
  const rawMappings = firstDefined([planRecord], ['fieldMappingDetails', 'field_mapping_details', 'fieldMappings', 'field_mappings', 'mappings']);
  const fieldEvidence = normalizeFieldEvidence(firstDefined([planRecord], ['fieldEvidence', 'field_evidence']), row.id);
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
    generationVersion: toText(firstDefined(records, ['generation_version', 'generationVersion', 'version'])) || '-',
    generatedAt: firstDefined(records, ['generated_at', 'generatedAt', 'created_at', 'createdAt', 'updated_at', 'updatedAt']) as string | number | undefined,
  };
}

function renderTextTags(values: string[], emptyText = '-') {
  if (!values.length) {
    return <span>{emptyText}</span>;
  }
  return (
    <Space size={[4, 6]} wrap>
      {values.map((value) => <Tag key={value} style={{ whiteSpace: 'normal', wordBreak: 'break-word', maxWidth: '100%' }}>{value}</Tag>)}
    </Space>
  );
}

export default function SchemaPage() {
  const [rows, setRows] = useState<SchemaTableRow[]>([]);
  const [sources, setSources] = useState<DataSourceRow[]>([]);
  const [fields, setFields] = useState<SchemaFieldRow[]>([]);
  const [mappings, setMappings] = useState<SchemaMappingRow[]>([]);
  const [scanRuns, setScanRuns] = useState<SchemaScanRunRow[]>([]);
  const [changes, setChanges] = useState<SchemaChangeEventRow[]>([]);
  const [plans, setPlans] = useState<IngestionPlanRow[]>([]);
  const [selectedTable, setSelectedTable] = useState<SchemaTableRow | null>(null);
  const [reasonPlan, setReasonPlan] = useState<IngestionPlanRow | null>(null);
  const [shadowValidationPlan, setShadowValidationPlan] = useState<IngestionPlanRow | null>(null);
  const [shadowValidationReport, setShadowValidationReport] = useState<IngestionPlanShadowValidationReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [planLoading, setPlanLoading] = useState(false);
  const [planGenerating, setPlanGenerating] = useState(false);
  const [changeActionId, setChangeActionId] = useState<number | null>(null);
  const [planActionId, setPlanActionId] = useState<number | null>(null);
  const [batchActionLoading, setBatchActionLoading] = useState(false);
  const [collectOpen, setCollectOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [planSourceId, setPlanSourceId] = useState<number | undefined>();
  const [planStatusFilter, setPlanStatusFilter] = useState<string | undefined>();
  const [collectForm] = Form.useForm<MetadataCollectValues>();
  const collectMode = Form.useWatch('collectMode', collectForm) || 'auto_scan';
  const previewFields = FIELD_PRESETS[collectMode];

  async function load() {
    setLoading(true);
    try {
      const [schemaRows, sourceRows, scanRunRows, changeRows] = await Promise.all([
        apiGet<SchemaTableRow[]>('/api/core/schema/tables'),
        apiGet<DataSourceRow[]>('/api/core/data-sources'),
        apiGet<SchemaScanRunRow[]>('/api/core/schema-scans/runs?limit=40'),
        apiGet<SchemaChangeEventRow[]>('/api/core/schema-scans/changes?limit=40'),
      ]);
      setRows(schemaRows);
      setSources(sourceRows);
      setScanRuns(scanRunRows);
      setChanges(changeRows);
      setPlanSourceId((current) => {
        if (current && sourceRows.some((source) => source.id === current)) {
          return current;
        }
        return sourceRows.find((source) => source.connection_kind === 'database')?.id ?? sourceRows[0]?.id;
      });
    } catch {
      setRows([]);
      setSources([]);
      setScanRuns([]);
      setChanges([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function loadPlans(sourceId = planSourceId, status = planStatusFilter) {
    setPlanLoading(true);
    try {
      const params = new URLSearchParams();
      params.set('dataSourceId', sourceId ? String(sourceId) : '');
      params.set('status', status && PLAN_STATUS_FILTER_VALUES.has(status) ? status : '');
      setPlans(await apiGet<IngestionPlanRow[]>(`/api/core/ingestion-plans?${params.toString()}`));
    } catch {
      setPlans([]);
    } finally {
      setPlanLoading(false);
    }
  }

  useEffect(() => {
    void loadPlans();
  }, [planSourceId, planStatusFilter]);

  async function refreshAll() {
    await Promise.all([load(), loadPlans()]);
  }

  function openCollect() {
    const firstSource = sources[0];
    collectForm.setFieldsValue({
      dataSourceId: firstSource?.id,
      collectMode: 'auto_scan',
      tableName: defaultTableName(),
      category: '告警事件',
      samplePayload: '{\n  "event_id": "EVT-20260520-001",\n  "event_name": "疑似敏感文件外发",\n  "risk_level": "high"\n}',
    });
    setCollectOpen(true);
  }

  async function startScanRun() {
    const firstSource = sources.find((source) => source.connection_kind === 'database') || sources[0];
    if (!firstSource) {
      message.warning('请先在数据源管理中新增外部系统接入');
      return;
    }
    const result = await apiPost<{
      status: string;
      scannedTables?: number;
      scannedFields?: number;
      changeCount?: number;
      pendingChangeCount?: number;
      autoAcceptedChangeCount?: number;
      errorMessage?: string;
    }>(
      '/api/core/schema-scans/execute',
      {
        dataSourceId: firstSource.id,
        tableLimit: 200,
        fieldLimit: 300,
        includeViews: false,
      },
    );
    if (result.status === 'success') {
      message.success(
        `元数据扫描完成：${result.scannedTables || 0} 张表，${result.scannedFields || 0} 个字段；${result.changeCount || 0} 项变化，${result.pendingChangeCount || 0} 项待确认`,
      );
    } else {
      message.error(result.errorMessage || '元数据扫描失败');
    }
    await load();
  }

  async function updateChangeStatus(id: number, action: 'accept' | 'ignore' | 'reopen') {
    setChangeActionId(id);
    try {
      await apiPut<SchemaChangeEventRow>(`/api/core/schema-scans/changes/${id}/status`, {
        action,
        operator: 'admin',
      });
      message.success(action === 'accept' ? '结构变化已确认' : action === 'ignore' ? '结构变化已忽略' : '结构变化已重新打开');
      await load();
    } finally {
      setChangeActionId(null);
    }
  }

  async function generateIngestionPlan() {
    if (!planSourceId) {
      message.warning('请先选择数据源');
      return;
    }
    setPlanGenerating(true);
    try {
      await apiPost<IngestionPlanRow[]>('/api/core/ingestion-plans/generate', {
        dataSourceId: planSourceId,
      });
      message.success('推荐接入方案已生成');
      setPlanStatusFilter(undefined);
      await loadPlans(planSourceId, undefined);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '推荐接入方案生成失败');
    } finally {
      setPlanGenerating(false);
    }
  }

  async function updatePlanStatus(row: IngestionPlanRow, status: string, successText: string) {
    setPlanActionId(row.id);
    try {
      await apiPut<IngestionPlanRow>(`/api/core/ingestion-plans/${row.id}/status`, {
        status,
      });
      message.success(successText);
      await loadPlans();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '推荐接入方案状态更新失败');
    } finally {
      setPlanActionId(null);
    }
  }

  async function shadowValidatePlan(row: IngestionPlanRow) {
    setPlanActionId(row.id);
    try {
      const report = await apiPost<IngestionPlanShadowValidationReport>(`/api/core/ingestion-plans/${row.id}/shadow-validate`, {
        sampleLimit: 50,
      });
      setShadowValidationPlan(row);
      setShadowValidationReport(report);
      message.success('试运行校验已完成');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '试运行校验失败');
    } finally {
      setPlanActionId(null);
    }
  }

  function confirmPendingChanges() {
    const ids = changes.filter((change) => change.status === 'pending').map((change) => change.id);
    if (!ids.length) {
      message.info('当前没有待确认的结构变化');
      return;
    }
    Modal.confirm({
      title: '批量确认待处理结构变化',
      content: `将确认 ${ids.length} 项结构变化，确认后采集和字段映射可以继续按当前快照执行。`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        setBatchActionLoading(true);
        try {
          await apiPost('/api/core/schema-scans/changes/batch-status', {
            ids,
            action: 'accept',
            operator: 'admin',
          });
          message.success(`已确认 ${ids.length} 项结构变化`);
          await load();
        } finally {
          setBatchActionLoading(false);
        }
      },
    });
  }

  async function saveSnapshot() {
    const values = await collectForm.validateFields();
    const table = await apiPost<{ id: number }>('/api/core/schema/tables', {
      dataSourceId: values.dataSourceId,
      tableName: values.tableName,
      category: values.category,
      confirmationStatus: 'confirmed',
    });

    await Promise.all(
      previewFields.map((field) =>
        apiPost(`/api/core/schema/tables/${table.id}/fields`, {
          fieldName: field.fieldName,
          fieldType: field.fieldType,
          nullable: field.nullable,
          sampleValue: field.sampleValue,
          description: field.description,
        }),
      ),
    );

    await Promise.all(
      previewFields
        .filter((field) => field.standardField)
        .map((field) =>
          apiPost('/api/core/schema/mappings', {
            schemaTableId: table.id,
            sourceField: field.fieldName,
            standardField: field.standardField,
            transformRule: field.transformRule || '自动推荐',
          }),
        ),
    );

    message.success('元数据快照已保存，字段映射已自动推荐');
    setCollectOpen(false);
    collectForm.resetFields();
    await load();
  }

  async function openDetail(row: SchemaTableRow) {
    setSelectedTable(row);
    setDetailOpen(true);
    setDetailLoading(true);
    try {
      const [fieldRows, mappingRows] = await Promise.all([
        apiGet<SchemaFieldRow[]>(`/api/core/schema/tables/${row.id}/fields`),
        apiGet<SchemaMappingRow[]>(`/api/core/schema/tables/${row.id}/mappings`),
      ]);
      setFields(fieldRows);
      setMappings(mappingRows);
    } catch {
      setFields([]);
      setMappings([]);
    } finally {
      setDetailLoading(false);
    }
  }

  const summary = useMemo(() => {
    const confirmed = rows.filter((row) => row.confirmation_status === 'confirmed').length;
    const pending = rows.filter((row) => row.confirmation_status !== 'confirmed').length;
    const pendingChanges = changes.filter((change) => change.status === 'pending').length;
    const autoAcceptedChanges = changes.filter((change) => change.status === 'auto_accepted').length;
    return {
      total: rows.length,
      confirmed,
      pending,
      pendingChanges,
      autoAcceptedChanges,
      autoHandled: Math.max(0, confirmed - pending),
    };
  }, [changes, rows]);

  const sourceOptions = sources.map((source) => ({
    value: source.id,
    label: source.name,
  }));

  const planViewRows = useMemo(() => plans.map((row) => ({
    row,
    plan: normalizePlan(row),
  })), [plans]);

  const reasonPlanView = useMemo(() => reasonPlan ? normalizePlan(reasonPlan) : null, [reasonPlan]);
  const shadowValidationPlanView = useMemo(
    () => shadowValidationPlan ? normalizePlan(shadowValidationPlan) : null,
    [shadowValidationPlan],
  );

  const mappingByField = useMemo(() => {
    const map = new Map<string, SchemaMappingRow>();
    mappings.forEach((mapping) => map.set(mapping.source_field, mapping));
    return map;
  }, [mappings]);

  const tableColumns: ColumnsType<SchemaTableRow> = [
    { title: '数据源', dataIndex: 'data_source_name' },
    {
      title: '表 / 文件 / 事件对象',
      dataIndex: 'table_name',
      render: (value: string) => <strong>{value}</strong>,
    },
    { title: '业务分类', dataIndex: 'category', render: (value) => value || '-' },
    {
      title: '确认状态',
      dataIndex: 'confirmation_status',
      width: 120,
      render: statusTag,
    },
    {
      title: '处理方式',
      width: 180,
      render: (_, row) => (row.confirmation_status === 'confirmed' ? <Tag color="success">自动确认</Tag> : <Tag color="warning">待运营确认</Tag>),
    },
    {
      title: '操作',
      width: 140,
      align: 'right',
      render: (_, row) => (
        <Button size="small" type="link" icon={<BranchesOutlined />} onClick={() => openDetail(row)}>
          字段映射
        </Button>
      ),
    },
  ];

  const fieldColumns: ColumnsType<SchemaFieldRow> = [
    {
      title: '字段',
      dataIndex: 'field_name',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">{row.description || '-'}</span>
        </div>
      ),
    },
    { title: '类型', dataIndex: 'field_type', width: 110 },
    { title: '识别结果', width: 120, render: (_, row) => riskTag(row.field_name, row.description) },
    {
      title: '标准字段',
      width: 190,
      render: (_, row) => {
        const mapped = mappingByField.get(row.field_name);
        const standard = mapped?.standard_field || recommendStandardField(row.field_name);
        return standard ? (
          <div>
            <Tag color={mapped ? 'success' : 'processing'}>{mapped ? '已映射' : '推荐'}</Tag>
            <span>{STANDARD_FIELD_LABELS[standard] || standard}</span>
          </div>
        ) : (
          <Tag>不参与规则</Tag>
        );
      },
    },
    { title: '样例值', dataIndex: 'sample_value', render: (value) => value || '-' },
  ];

  const previewColumns: ColumnsType<SuggestedField> = [
    { title: '字段', dataIndex: 'fieldName' },
    { title: '类型', dataIndex: 'fieldType', width: 110 },
    { title: '识别结果', width: 120, render: (_, row) => riskTag(row.fieldName, row.description) },
    {
      title: '推荐映射',
      width: 170,
      render: (_, row) => row.standardField ? <Tag color="processing">{STANDARD_FIELD_LABELS[row.standardField] || row.standardField}</Tag> : <Tag>不参与规则</Tag>,
    },
    { title: '说明', dataIndex: 'description' },
  ];

  const scanColumns: ColumnsType<SchemaScanRunRow> = [
    {
      title: '数据源',
      dataIndex: 'data_source_name',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">{row.scan_type}</span>
        </div>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 110, render: scanStatusTag },
    { title: '库', width: 90, render: (_, row) => `${row.scanned_databases || 0}/${row.total_databases || 0}` },
    { title: '表', width: 90, render: (_, row) => `${row.scanned_tables || 0}/${row.total_tables || 0}` },
    { title: '字段', width: 100, render: (_, row) => `${row.scanned_fields || 0}/${row.total_fields || 0}` },
    { title: '开始时间', dataIndex: 'started_at', width: 150, render: formatTime },
    { title: '完成时间', dataIndex: 'finished_at', width: 150, render: formatTime },
    { title: '错误信息', dataIndex: 'error_message', render: (value) => value || '-' },
  ];

  const changeColumns: ColumnsType<SchemaChangeEventRow> = [
    {
      title: '对象',
      dataIndex: 'object_name',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">{row.data_source_name}</span>
        </div>
      ),
    },
    { title: '变化类型', dataIndex: 'change_type', width: 120, render: changeTypeTag },
    { title: '风险', dataIndex: 'severity', width: 90, render: severityTag },
    { title: '处理状态', dataIndex: 'status', width: 120, render: changeStatusTag },
    { title: '原因', dataIndex: 'reason', render: (value) => value || '-' },
    { title: '发现时间', dataIndex: 'created_at', width: 150, render: formatTime },
    {
      title: '操作',
      width: 170,
      align: 'right',
      render: (_, row) => row.status === 'pending' ? (
        <Space size={4}>
          <Button
            size="small"
            type="link"
            loading={changeActionId === row.id}
            onClick={() => updateChangeStatus(row.id, 'accept')}
          >
            确认
          </Button>
          <Button
            size="small"
            type="link"
            danger
            loading={changeActionId === row.id}
            onClick={() => updateChangeStatus(row.id, 'ignore')}
          >
            忽略
          </Button>
        </Space>
      ) : (
        <Button
          size="small"
          type="link"
          loading={changeActionId === row.id}
          onClick={() => updateChangeStatus(row.id, 'reopen')}
        >
          重新打开
        </Button>
      ),
    },
  ];

  function renderPlanActions(row: IngestionPlanRow, plan: NormalizedIngestionPlan) {
    const isBusy = planActionId === row.id;
    const status = plan.status;
    const canReview = !['review', 'review_required', 'approved', 'shadow_ready', 'rejected'].includes(status);
    const canApprove = !['approved', 'shadow_ready', 'rejected'].includes(status);
    const canReject = !['approved', 'shadow_ready', 'rejected'].includes(status);
    const canPrepareShadow = status === 'approved';
    const canShadowValidate = status === 'approved' || status === 'shadow_ready';
    const canDiscard = status === 'approved' || status === 'shadow_ready';
    const canRestore = status === 'rejected';

    return (
      <Space size={[4, 6]} wrap style={{ justifyContent: 'flex-end' }}>
        <Button size="small" type="link" onClick={() => setReasonPlan(row)}>
          查看原因
        </Button>
        {canReview && (
          <Button size="small" loading={isBusy} onClick={() => updatePlanStatus(row, 'review_required', '推荐方案已标记复核')}>
            标记复核
          </Button>
        )}
        {canApprove && (
          <Button size="small" type="primary" loading={isBusy} onClick={() => updatePlanStatus(row, 'approved', '推荐方案已批准')}>
            批准方案
          </Button>
        )}
        {canPrepareShadow && (
          <Button size="small" loading={isBusy} onClick={() => updatePlanStatus(row, 'shadow_ready', '推荐方案已进入试运行准备')}>
            进入试运行准备
          </Button>
        )}
        {canShadowValidate && (
          <Button size="small" icon={<SafetyCertificateOutlined />} loading={isBusy} onClick={() => shadowValidatePlan(row)}>
            试运行校验
          </Button>
        )}
        {canReject && (
          <Button size="small" danger loading={isBusy} onClick={() => updatePlanStatus(row, 'rejected', '推荐方案已拒绝')}>
            拒绝
          </Button>
        )}
        {canDiscard && (
          <Button size="small" danger loading={isBusy} onClick={() => updatePlanStatus(row, 'rejected', '推荐方案已废弃')}>
            废弃方案
          </Button>
        )}
        {canRestore && (
          <Button size="small" loading={isBusy} onClick={() => updatePlanStatus(row, 'suggested', '推荐方案已重新推荐')}>
            重新推荐
          </Button>
        )}
      </Space>
    );
  }

  function renderPlanDetailSection(title: string, content: ReactNode) {
    return (
      <div style={{ minWidth: 0 }}>
        <Typography.Text strong>{title}</Typography.Text>
        <div style={{ marginTop: 8, color: '#4b5565', lineHeight: 1.7, wordBreak: 'break-word' }}>
          {content}
        </div>
      </div>
    );
  }

  function renderPlanPanel(row: IngestionPlanRow, plan: NormalizedIngestionPlan) {
    return (
      <div
        key={row.id}
        style={{
          display: 'grid',
          gap: 14,
          minWidth: 0,
          padding: 14,
          background: '#f8fafc',
          border: '1px solid #e4ebf4',
          borderRadius: 10,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', minWidth: 0 }}>
          <div style={{ minWidth: 220, flex: '1 1 320px' }}>
            <Typography.Text strong style={{ display: 'block', fontSize: 15, wordBreak: 'break-word' }}>
              {plan.candidateTable !== '-' ? plan.candidateTable : plan.name}
            </Typography.Text>
            <Space size={[6, 6]} wrap style={{ marginTop: 6 }}>
              {planStatusTag(plan.status)}
              {renderWrappedTag(plan.templateType)}
              <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{plan.dataSourceName}</Typography.Text>
            </Space>
          </div>
          <div style={{ flex: '1 1 360px', textAlign: 'right' }}>
            {renderPlanActions(row, plan)}
          </div>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(128px, 1fr))', gap: 10 }}>
          {[
            ['综合置信度', formatConfidence(plan.overallConfidence)],
            ['模板置信度', formatConfidence(plan.templateConfidence)],
            ['覆盖置信度', formatConfidence(plan.coverageConfidence)],
            ['映射完整度', formatConfidence(plan.mappingCompleteness)],
          ].map(([label, value]) => (
            <div key={label} style={{ minWidth: 0, padding: '10px 12px', background: '#fff', border: '1px solid #e4ebf4', borderRadius: 8 }}>
              <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>{label}</Typography.Text>
              <Typography.Text strong style={{ fontSize: 18 }}>{value}</Typography.Text>
            </div>
          ))}
        </div>

        <Descriptions bordered size="small" column={{ xs: 1, sm: 1, md: 2 }}>
          <Descriptions.Item label="候选表">{plan.candidateTable}</Descriptions.Item>
          <Descriptions.Item label="模板类型">{plan.templateType}</Descriptions.Item>
          <Descriptions.Item label="生成版本">{plan.generationVersion}</Descriptions.Item>
          <Descriptions.Item label="生成时间">{formatTime(plan.generatedAt)}</Descriptions.Item>
          <Descriptions.Item label="当前状态">{planStatusTag(plan.status)}</Descriptions.Item>
        </Descriptions>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 14 }}>
          {renderPlanDetailSection(
            '字段映射',
            plan.fieldMappings.length ? (
              <div style={{ display: 'grid', gap: 8 }}>
                {plan.fieldMappings.map((mapping) => (
                  <div key={mapping.key} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', minWidth: 0 }}>
                    {renderWrappedTag(mapping.sourceField || '-', 'processing')}
                    <span>映射到</span>
                    {renderWrappedTag(STANDARD_FIELD_LABELS[mapping.standardField] || mapping.standardField || '-', 'success')}
                    {mapping.confidence !== undefined && <Typography.Text type="secondary">{formatConfidence(mapping.confidence)}</Typography.Text>}
                    {mapping.transformRule && <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{mapping.transformRule}</Typography.Text>}
                  </div>
                ))}
              </div>
            ) : '-',
          )}
          {renderPlanDetailSection(
            '去重策略',
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              <div>字段：{renderTextTags(plan.dedupFields, '未配置')}</div>
              {plan.dedupRule && <div>规则：{plan.dedupRule}</div>}
              {plan.dedupWindow && <div>窗口：{plan.dedupWindow}</div>}
            </Space>,
          )}
          {renderPlanDetailSection('缺失字段', renderTextTags(plan.missingFields, '无'))}
          {renderPlanDetailSection('风险提示', renderTextTags(plan.risks, '无'))}
          {renderPlanDetailSection('推荐动作', renderTextTags(plan.recommendedActions, '无'))}
        </div>
      </div>
    );
  }

  return (
    <div className="schema-page">
      <div className="ops-heading">
        <div>
          <h3 className="ant-typography">元数据快照</h3>
          <span>自动采集表、字段、样例值和字段映射，只把关键变更交给用户确认</span>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading || planLoading} onClick={refreshAll}>
            刷新
          </Button>
          <Button icon={<FileSearchOutlined />} onClick={startScanRun}>
            立即扫描元数据
          </Button>
          <Button type="primary" icon={<CloudSyncOutlined />} onClick={openCollect}>
            采集元数据
          </Button>
        </Space>
      </div>

      <div className="schema-summary-grid">
        <Card className="ops-card">
          <Statistic title="快照对象" value={summary.total} prefix={<TableOutlined />} />
        </Card>
        <Card className="ops-card">
          <Statistic title="自动确认" value={summary.confirmed} prefix={<CheckCircleOutlined />} valueStyle={{ color: '#137c72' }} />
        </Card>
        <Card className="ops-card">
          <Statistic title="待确认" value={summary.pending} prefix={<WarningOutlined />} valueStyle={{ color: summary.pending > 0 ? '#c46a00' : '#137c72' }} />
        </Card>
        <Card className="ops-card">
          <Statistic title="字段变化待确认" value={summary.pendingChanges} prefix={<WarningOutlined />} valueStyle={{ color: summary.pendingChanges > 0 ? '#c46a00' : '#137c72' }} />
        </Card>
        <Card className="ops-card">
          <Statistic title="变化自动处理" value={summary.autoAcceptedChanges} prefix={<SafetyCertificateOutlined />} valueStyle={{ color: '#137c72' }} />
        </Card>
      </div>

      <Card className="ops-card" title="推荐接入方案">
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
          <Space size={[8, 8]} wrap>
            <Select
              allowClear
              placeholder="全部数据源"
              options={sourceOptions}
              value={planSourceId}
              style={{ minWidth: 220 }}
              onChange={(value) => setPlanSourceId(value)}
            />
            <Select
              allowClear
              placeholder="全部状态"
              options={PLAN_STATUS_FILTER_OPTIONS}
              value={planStatusFilter}
              style={{ minWidth: 150 }}
              onChange={(value) => setPlanStatusFilter(value)}
            />
          </Space>
          <Space size={[8, 8]} wrap>
            <Button loading={planLoading} onClick={() => loadPlans()}>
              刷新方案
            </Button>
            <Button type="primary" loading={planGenerating} disabled={!planSourceId} onClick={generateIngestionPlan}>
              生成推荐方案
            </Button>
          </Space>
        </div>

        {planViewRows.length ? (
          <div style={{ display: 'grid', gap: 14, minWidth: 0 }}>
            {planViewRows.map(({ row, plan }) => renderPlanPanel(row, plan))}
          </div>
        ) : (
          <div style={{ padding: 24, textAlign: 'center', color: '#7a8798', background: '#f8fafc', border: '1px dashed #d7e0ec', borderRadius: 10 }}>
            {planLoading ? '推荐接入方案加载中' : '暂无推荐接入方案'}
          </div>
        )}
      </Card>

      <Card className="ops-card" title="扫描运行记录">
        <Table<SchemaScanRunRow>
          rowKey="id"
          loading={loading}
          dataSource={scanRuns}
          columns={scanColumns}
          pagination={{ pageSize: 5 }}
          scroll={{ x: 960 }}
          locale={{ emptyText: '暂无扫描运行记录。数据库、API、Webhook 或文件适配器执行结构发现后会写入这里。' }}
        />
      </Card>

      <Card
        className="ops-card"
        title="结构变化记录"
        extra={(
          <Button size="small" loading={batchActionLoading} onClick={confirmPendingChanges}>
            批量确认待处理
          </Button>
        )}
      >
        <Table<SchemaChangeEventRow>
          rowKey="id"
          loading={loading}
          dataSource={changes}
          columns={changeColumns}
          pagination={{ pageSize: 6 }}
          scroll={{ x: 1120 }}
          locale={{ emptyText: '暂无结构变化。二次扫描发现新增字段、删除字段或类型变化后会显示在这里。' }}
        />
      </Card>

      <Card className="ops-card" title="快照列表">
        <Table<SchemaTableRow>
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={tableColumns}
          pagination={{ pageSize: 8 }}
          locale={{ emptyText: '暂无元数据快照。先从数据源采集结构或导入样例数据。' }}
        />
      </Card>

      <Modal
        width={980}
        title="采集元数据"
        open={collectOpen}
        onOk={saveSnapshot}
        onCancel={() => setCollectOpen(false)}
        okText="保存快照"
        destroyOnHidden
      >
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="正式环境会自动扫描数据库结构、解析接口样例或导入文件样例。低风险字段自动纳入快照，关键字段和敏感字段由系统推荐映射。"
        />
        <Steps
          className="metadata-steps"
          current={2}
          items={[
            { title: '选择数据源', icon: <DatabaseOutlined /> },
            { title: '采集结构', icon: <FileSearchOutlined /> },
            { title: '智能识别', icon: <SafetyCertificateOutlined /> },
            { title: '保存快照', icon: <CheckCircleOutlined /> },
          ]}
        />
        <Form form={collectForm} layout="vertical">
          <Space className="metadata-form-grid" align="start">
            <Form.Item name="dataSourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
              <Select options={sourceOptions} placeholder="请选择数据源" />
            </Form.Item>
            <Form.Item name="collectMode" label="采集方式" rules={[{ required: true }]}>
              <Select options={COLLECT_MODE_OPTIONS} />
            </Form.Item>
          </Space>
          <Space className="metadata-form-grid" align="start">
            <Form.Item name="tableName" label="对象名称" rules={[{ required: true, message: '请输入对象名称' }]}>
              <Input placeholder="例如：security_alert_event、events.json、audit.csv" />
            </Form.Item>
            <Form.Item name="category" label="业务分类">
              <Input placeholder="例如：告警事件、账号行为、文件外发" />
            </Form.Item>
          </Space>
          {(collectMode === 'sample_json' || collectMode === 'file_sample') && (
            <Form.Item name="samplePayload" label="样例数据">
              <Input.TextArea rows={5} placeholder="粘贴一条 JSON，或粘贴 CSV 表头和第一行样例" />
            </Form.Item>
          )}
        </Form>

        <div className="metadata-auto-summary">
          <span><b>{previewFields.length}</b> 个字段</span>
          <span><b>{previewFields.filter((field) => field.standardField).length}</b> 个推荐映射</span>
          <span><b>{previewFields.filter((field) => /phone|mobile|id_card|email|bank|address|customer/.test(field.fieldName)).length}</b> 个敏感候选</span>
          <span><b>0</b> 个必须人工处理</span>
        </div>

        <Table<SuggestedField>
          size="small"
          rowKey="fieldName"
          dataSource={previewFields}
          columns={previewColumns}
          pagination={false}
        />
      </Modal>

      <Drawer
        title={selectedTable ? `字段映射：${selectedTable.table_name}` : '字段映射'}
        width={980}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
      >
        <Descriptions className="form-hint" bordered size="small" column={2}>
          <Descriptions.Item label="数据源">{selectedTable?.data_source_name || '-'}</Descriptions.Item>
          <Descriptions.Item label="业务分类">{selectedTable?.category || '-'}</Descriptions.Item>
          <Descriptions.Item label="确认状态">{statusTag(selectedTable?.confirmation_status)}</Descriptions.Item>
          <Descriptions.Item label="处理策略">低风险自动确认，关键字段自动推荐</Descriptions.Item>
        </Descriptions>
        <Table<SchemaFieldRow>
          rowKey="id"
          loading={detailLoading}
          dataSource={fields}
          columns={fieldColumns}
          pagination={{ pageSize: 8 }}
          locale={{ emptyText: '暂无字段。请先采集元数据或导入样例。' }}
        />
      </Drawer>

      <Drawer
        title={reasonPlanView ? `推荐原因：${reasonPlanView.candidateTable !== '-' ? reasonPlanView.candidateTable : reasonPlanView.name}` : '推荐原因'}
        width={860}
        open={Boolean(reasonPlan)}
        onClose={() => setReasonPlan(null)}
      >
        {reasonPlanView && (
          <div style={{ display: 'grid', gap: 16 }}>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="候选表">{reasonPlanView.candidateTable}</Descriptions.Item>
              <Descriptions.Item label="模板类型">{reasonPlanView.templateType}</Descriptions.Item>
              <Descriptions.Item label="综合置信度">{formatConfidence(reasonPlanView.overallConfidence)}</Descriptions.Item>
              <Descriptions.Item label="当前状态">{planStatusTag(reasonPlanView.status)}</Descriptions.Item>
              <Descriptions.Item label="生成版本">{reasonPlanView.generationVersion}</Descriptions.Item>
              <Descriptions.Item label="生成时间">{formatTime(reasonPlanView.generatedAt)}</Descriptions.Item>
            </Descriptions>

            {renderPlanDetailSection('判断依据', reasonPlanView.reasons.length ? (
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {reasonPlanView.reasons.map((reason) => <li key={reason}>{reason}</li>)}
              </ul>
            ) : '后端未返回详细原因')}
            {renderPlanDetailSection('字段映射原因', reasonPlanView.fieldMappings.length ? (
              <div style={{ display: 'grid', gap: 10 }}>
                {reasonPlanView.fieldMappings.map((mapping) => (
                  <div key={mapping.key} style={{ display: 'grid', gap: 4, paddingBottom: 10, borderBottom: '1px solid #edf2f7' }}>
                    <Space size={[6, 6]} wrap>
                      {renderWrappedTag(mapping.sourceField || '-', 'processing')}
                      <span>映射到</span>
                      {renderWrappedTag(STANDARD_FIELD_LABELS[mapping.standardField] || mapping.standardField || '-', 'success')}
                      {mapping.confidence !== undefined && <Typography.Text type="secondary">{formatConfidence(mapping.confidence)}</Typography.Text>}
                    </Space>
                    <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{mapping.reason || mapping.transformRule || '未返回单字段原因'}</Typography.Text>
                  </div>
                ))}
              </div>
            ) : '-')}
            {renderPlanDetailSection('风险提示', renderTextTags(reasonPlanView.risks, '无'))}
            {renderPlanDetailSection('推荐动作', renderTextTags(reasonPlanView.recommendedActions, '无'))}
          </div>
        )}
      </Drawer>

      <Drawer
        title={shadowValidationPlanView ? `试运行校验：${shadowValidationPlanView.candidateTable !== '-' ? shadowValidationPlanView.candidateTable : shadowValidationPlanView.name}` : '试运行校验'}
        width={900}
        open={Boolean(shadowValidationReport)}
        onClose={() => {
          setShadowValidationPlan(null);
          setShadowValidationReport(null);
        }}
      >
        {shadowValidationReport && (
          <div style={{ display: 'grid', gap: 16 }}>
            <Descriptions bordered size="small" column={{ xs: 1, sm: 1, md: 2 }}>
              <Descriptions.Item label="校验结果">{shadowValidationResultTag(shadowValidationReport.result)}</Descriptions.Item>
              <Descriptions.Item label="方案状态">{planStatusTag(shadowValidationReport.planStatus)}</Descriptions.Item>
              <Descriptions.Item label="推荐状态">{shadowValidationReport.statusRecommendation || '-'}</Descriptions.Item>
              <Descriptions.Item label="样本上限">{shadowValidationReport.sampleLimit ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="候选表">{shadowValidationReport.mainTable || '-'}</Descriptions.Item>
              <Descriptions.Item label="模板类型">{shadowValidationReport.templateKey || '-'}</Descriptions.Item>
              <Descriptions.Item label="映射字段数">{shadowValidationReport.mappedFieldCount ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="校验时间">{formatTime(shadowValidationReport.checkedAt)}</Descriptions.Item>
            </Descriptions>

            {renderPlanDetailSection('阻断项', renderTextTags(shadowValidationReport.blockers || [], '无'))}
            {renderPlanDetailSection('提醒项', renderTextTags(shadowValidationReport.warnings || [], '无'))}
            {renderPlanDetailSection(
              '标准事件预览',
              shadowValidationReport.standardEventPreview && Object.keys(shadowValidationReport.standardEventPreview).length ? (
                <Descriptions bordered size="small" column={1}>
                  {Object.entries(shadowValidationReport.standardEventPreview).map(([field, value]) => (
                    <Descriptions.Item key={field} label={STANDARD_FIELD_LABELS[field] || field}>
                      <Typography.Text style={{ wordBreak: 'break-word' }}>{formatUnknownValue(value) || '-'}</Typography.Text>
                    </Descriptions.Item>
                  ))}
                </Descriptions>
              ) : '-',
            )}
            {renderPlanDetailSection(
              '校验项',
              shadowValidationReport.checks?.length ? (
                <div style={{ display: 'grid', gap: 10 }}>
                  {shadowValidationReport.checks.map((check, index) => (
                    <div key={`${check.code}-${index}`} style={{ display: 'grid', gap: 4, paddingBottom: 10, borderBottom: '1px solid #edf2f7' }}>
                      <Space size={[6, 6]} wrap>
                        {shadowValidationResultTag(check.result)}
                        <Typography.Text strong style={{ wordBreak: 'break-word' }}>{check.code}</Typography.Text>
                      </Space>
                      {check.message && <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{check.message}</Typography.Text>}
                      {!!check.blockers?.length && <div>{renderTextTags(check.blockers)}</div>}
                      {check.details !== undefined && (
                        <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{formatUnknownValue(check.details)}</Typography.Text>
                      )}
                    </div>
                  ))}
                </div>
              ) : '-',
            )}
          </div>
        )}
      </Drawer>
    </div>
  );
}
