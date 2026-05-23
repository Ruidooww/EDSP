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
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, Modal, Select, Space, Statistic, Steps, Table, Tag, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost, apiPut } from '../../api';
import type {
  DataSourceRow,
  IngestionPlanRow,
  IngestionPlanShadowRunRow,
  IngestionPlanShadowValidationReport,
  SchemaChangeEventRow,
  SchemaFieldRow,
  SchemaScanRunRow,
  SchemaTableRow,
} from '../../types';
import IngestionPlanPrecheckDrawer from './components/IngestionPlanPrecheckDrawer';
import IngestionPlanReasonDrawer from './components/IngestionPlanReasonDrawer';
import IngestionPlanSection from './components/IngestionPlanSection';
import IngestionPlanShadowRunReportDrawer from './components/IngestionPlanShadowRunReportDrawer';
import {
  PLAN_STATUS_FILTER_VALUES,
  STANDARD_FIELD_LABELS,
} from './utils/ingestionPlanLabels';
import { normalizePlan } from './utils/normalizeIngestionPlan';

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
  const [shadowRunPlan, setShadowRunPlan] = useState<IngestionPlanRow | null>(null);
  const [shadowRunReport, setShadowRunReport] = useState<IngestionPlanShadowRunRow | null>(null);
  const [shadowRunsByPlanId, setShadowRunsByPlanId] = useState<Record<number, IngestionPlanShadowRunRow>>({});
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
      if (sourceId !== undefined && sourceId !== null) {
        params.set('dataSourceId', String(sourceId));
      }
      if (status && PLAN_STATUS_FILTER_VALUES.has(status)) {
        params.set('status', status);
      }
      const query = params.toString();
      setPlans(await apiGet<IngestionPlanRow[]>(`/api/core/ingestion-plans${query ? `?${query}` : ''}`));
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
      message.success('试运行前校验 / Shadow Precheck 已完成');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '试运行前校验 / Shadow Precheck 失败');
    } finally {
      setPlanActionId(null);
    }
  }

  async function executeShadowRun(row: IngestionPlanRow) {
    setPlanActionId(row.id);
    try {
      const precheck = await apiPost<IngestionPlanShadowValidationReport>(`/api/core/ingestion-plans/${row.id}/shadow-validate`, {
        sampleLimit: 50,
      });
      if (precheck.result !== 'passed' && precheck.result !== 'warning') {
        setShadowValidationPlan(row);
        setShadowValidationReport(precheck);
        message.warning('试运行前校验未通过，未创建 Shadow Run');
        return;
      }
      const run = await apiPost<IngestionPlanShadowRunRow>(`/api/core/ingestion-plans/${row.id}/shadow-runs`, {
        sampleLimit: 50,
      });
      setShadowRunsByPlanId((current) => ({ ...current, [row.id]: run }));
      setShadowRunPlan(row);
      setShadowRunReport(run);
      if (run.status === 'failed' || run.status === 'blocked') {
        message.warning('Shadow Run 已完成，请查看报告中的异常原因');
      } else {
        message.success('Shadow Run 试运行已完成');
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Shadow Run 试运行失败');
    } finally {
      setPlanActionId(null);
    }
  }

  async function viewShadowRunReport(row: IngestionPlanRow) {
    const cachedRun = shadowRunsByPlanId[row.id];
    if (cachedRun?.report) {
      setShadowRunPlan(row);
      setShadowRunReport(cachedRun);
      return;
    }
    setPlanActionId(row.id);
    try {
      const runs = await apiGet<IngestionPlanShadowRunRow[]>(`/api/core/ingestion-plans/${row.id}/shadow-runs?limit=1`);
      const latestRun = runs[0];
      if (!latestRun) {
        message.info('暂无试运行报告，请先执行试运行');
        return;
      }
      const detail = await apiGet<IngestionPlanShadowRunRow>(`/api/core/ingestion-plan-shadow-runs/${latestRun.id}`);
      setShadowRunsByPlanId((current) => ({ ...current, [row.id]: detail }));
      setShadowRunPlan(row);
      setShadowRunReport(detail);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '试运行报告加载失败');
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
  const shadowRunPlanView = useMemo(
    () => shadowRunPlan ? normalizePlan(shadowRunPlan) : null,
    [shadowRunPlan],
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

      <IngestionPlanSection
        sourceOptions={sourceOptions}
        sourceId={planSourceId}
        status={planStatusFilter}
        rows={planViewRows}
        loading={planLoading}
        generating={planGenerating}
        actionId={planActionId}
        formatTime={formatTime}
        onSourceChange={setPlanSourceId}
        onStatusChange={setPlanStatusFilter}
        onRefresh={() => loadPlans()}
        onGenerate={generateIngestionPlan}
        onViewReason={setReasonPlan}
        onUpdateStatus={updatePlanStatus}
        onShadowValidate={shadowValidatePlan}
        onShadowRun={executeShadowRun}
        onViewShadowReport={viewShadowRunReport}
      />

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

      <IngestionPlanReasonDrawer
        plan={reasonPlanView}
        open={Boolean(reasonPlan)}
        formatTime={formatTime}
        onClose={() => setReasonPlan(null)}
      />
      <IngestionPlanPrecheckDrawer
        plan={shadowValidationPlanView}
        report={shadowValidationReport}
        formatTime={formatTime}
        onClose={() => {
          setShadowValidationPlan(null);
          setShadowValidationReport(null);
        }}
      />
      <IngestionPlanShadowRunReportDrawer
        plan={shadowRunPlanView}
        run={shadowRunReport}
        formatTime={formatTime}
        onClose={() => {
          setShadowRunPlan(null);
          setShadowRunReport(null);
        }}
      />
    </div>
  );
}
