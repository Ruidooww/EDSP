import { PlayCircleOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import {
  Alert as AntAlert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost, apiPut } from '../api';
import type {
  RuleDecision,
  RuleEvaluationDecisionRow,
  RuleEvaluationRunRequest,
  RuleEvaluationRunResult,
  RuleRequest,
  RuleRow,
  Severity,
} from '../types';

interface RuleFormValues {
  name: string;
  eventType: string;
  severity: Severity;
  minSeverity: Severity;
  threshold: number;
  enabled: boolean;
}

interface EvaluationFormValues {
  standardEventId: number;
  ruleId?: number;
  operatorName?: string;
}

interface StructuredRuleExpression {
  version: number;
  mode: 'structured_config';
  timeWindow: 'all_day';
  minSeverity: Severity;
  threshold: {
    metric: 'riskScore';
    operator: '>=';
    value: number;
  };
}

const eventTypeOptions = [
  { value: 'file_operation', label: 'file_operation' },
  { value: 'file_transfer', label: 'file_transfer' },
  { value: 'device_operation', label: 'device_operation' },
  { value: 'data_access', label: 'data_access' },
  { value: 'account_activity', label: 'account_activity' },
];

const severityOptions: Array<{ value: Severity; label: string }> = [
  { value: 'critical', label: 'critical' },
  { value: 'high', label: 'high' },
  { value: 'medium', label: 'medium' },
  { value: 'low', label: 'low' },
  { value: 'info', label: 'info' },
];

const decisionColors: Record<RuleDecision, string> = {
  matched: 'red',
  not_matched: 'default',
  skipped: 'gold',
  error: 'volcano',
};

function severityColor(value?: string) {
  return {
    critical: 'red',
    high: 'orange',
    medium: 'gold',
    low: 'green',
    info: 'cyan',
  }[value ?? ''] ?? 'default';
}

function ruleEventType(row: RuleRow) {
  return row.eventType ?? row.event_type;
}

function decisionStandardEventId(row: RuleEvaluationDecisionRow) {
  return row.standardEventId ?? row.standard_event_id;
}

function decisionRuleId(row: RuleEvaluationDecisionRow) {
  return row.ruleId ?? row.rule_id;
}

function decisionRuleName(row: RuleEvaluationDecisionRow) {
  return row.ruleName ?? row.rule_name ?? `#${decisionRuleId(row) ?? '-'}`;
}

function decisionRiskScore(row: RuleEvaluationDecisionRow) {
  return row.riskScore ?? row.risk_score;
}

function decisionCreatedAt(row: RuleEvaluationDecisionRow) {
  return row.createdAt ?? row.created_at;
}

function buildRuleExpression(values: RuleFormValues) {
  const model: StructuredRuleExpression = {
    version: 1,
    mode: 'structured_config',
    timeWindow: 'all_day',
    minSeverity: values.minSeverity,
    threshold: {
      metric: 'riskScore',
      operator: '>=',
      value: values.threshold,
    },
  };
  return JSON.stringify(model);
}

function parseRuleExpression(expression: string): Partial<StructuredRuleExpression> {
  try {
    const parsed = JSON.parse(expression) as Partial<StructuredRuleExpression>;
    if (parsed?.mode === 'structured_config') {
      return parsed;
    }
  } catch {
    return {};
  }
  return {};
}

function formatDate(value?: string | number) {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString();
}

function evaluationSummary(result?: RuleEvaluationRunResult) {
  if (!result) {
    return '尚未执行';
  }
  return `evaluated ${result.evaluatedCount}, matched ${result.matchedCount ?? 0}, not_matched ${
    result.notMatchedCount ?? 0
  }, skipped ${result.skippedCount ?? 0}, error ${result.errorCount ?? 0}`;
}

export default function RulesPage() {
  const [rows, setRows] = useState<RuleRow[]>([]);
  const [decisions, setDecisions] = useState<RuleEvaluationDecisionRow[]>([]);
  const [lastResult, setLastResult] = useState<RuleEvaluationRunResult>();
  const [loading, setLoading] = useState(false);
  const [decisionLoading, setDecisionLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<RuleFormValues>();
  const [evaluationForm] = Form.useForm<EvaluationFormValues>();

  const ruleOptions = useMemo(
    () =>
      rows
        .filter((row) => row.enabled)
        .map((row) => ({ value: row.id, label: `${row.name} (#${row.id})` })),
    [rows],
  );

  async function loadRules() {
    setLoading(true);
    try {
      setRows(await apiGet<RuleRow[]>('/api/core/rules?limit=100'));
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }

  async function loadDecisions(standardEventId?: number) {
    setDecisionLoading(true);
    try {
      const query = new URLSearchParams({ limit: '50' });
      if (standardEventId) {
        query.set('standardEventId', String(standardEventId));
      }
      setDecisions(await apiGet<RuleEvaluationDecisionRow[]>(`/api/core/rule-evaluations?${query.toString()}`));
    } catch {
      setDecisions([]);
    } finally {
      setDecisionLoading(false);
    }
  }

  async function load() {
    await Promise.all([loadRules(), loadDecisions(evaluationForm.getFieldValue('standardEventId'))]);
  }

  useEffect(() => {
    void load();
  }, []);

  function openCreate() {
    form.setFieldsValue({
      name: 'High risk standard event',
      eventType: 'file_operation',
      severity: 'high',
      minSeverity: 'medium',
      threshold: 80,
      enabled: true,
    });
    setOpen(true);
  }

  async function submit() {
    const values = await form.validateFields();
    const payload: RuleRequest = {
      name: values.name,
      eventType: values.eventType,
      severity: values.severity,
      expression: buildRuleExpression(values),
      enabled: values.enabled,
    };
    await apiPost('/api/core/rules', payload);
    message.success('规则已创建');
    setOpen(false);
    form.resetFields();
    await loadRules();
  }

  async function toggleEnabled(row: RuleRow, enabled: boolean) {
    await apiPut(`/api/core/rules/${row.id}/enabled`, { enabled });
    message.success(enabled ? '规则已启用' : '规则已停用');
    await loadRules();
  }

  async function runEvaluation() {
    const values = await evaluationForm.validateFields();
    const payload: RuleEvaluationRunRequest = {
      standardEventId: values.standardEventId,
      ruleId: values.ruleId,
      operatorName: values.operatorName,
    };
    const result = await apiPost<RuleEvaluationRunResult>('/api/core/rule-evaluations/run', payload);
    setLastResult(result);
    message.success('规则评估已执行');
    await loadDecisions(values.standardEventId);
  }

  const ruleColumns: ColumnsType<RuleRow> = [
    {
      title: '规则名称',
      dataIndex: 'name',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">{ruleEventType(row)}</span>
        </div>
      ),
    },
    {
      title: '配置',
      dataIndex: 'expression',
      width: 240,
      render: (expression: string) => {
        const model = parseRuleExpression(expression);
        return (
          <div>
            <span>riskScore &gt;= {model.threshold?.value ?? '-'}</span>
            <span className="table-subtext">minSeverity: {model.minSeverity ?? '-'}</span>
          </div>
        );
      },
    },
    {
      title: '输出等级',
      dataIndex: 'severity',
      width: 110,
      render: (value) => <Tag color={severityColor(value)}>{value}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (value, row) => <Switch size="small" checked={value} onChange={(checked) => toggleEnabled(row, checked)} />,
    },
  ];

  const decisionColumns: ColumnsType<RuleEvaluationDecisionRow> = [
    {
      title: 'standardEventId',
      width: 150,
      render: (_, row) => decisionStandardEventId(row) ?? '-',
    },
    {
      title: '规则',
      render: (_, row) => decisionRuleName(row),
    },
    {
      title: 'decision',
      width: 130,
      dataIndex: 'decision',
      render: (value: RuleDecision) => <Tag color={decisionColors[value] ?? 'default'}>{value}</Tag>,
    },
    {
      title: 'reason',
      dataIndex: 'reason',
      width: 190,
      render: (value?: string) => value ?? '-',
    },
    {
      title: 'severity',
      dataIndex: 'severity',
      width: 110,
      render: (value?: string) => <Tag color={severityColor(value)}>{value ?? '-'}</Tag>,
    },
    {
      title: 'riskScore',
      width: 110,
      render: (_, row) => decisionRiskScore(row) ?? '-',
    },
    {
      title: 'createdAt',
      width: 190,
      render: (_, row) => formatDate(decisionCreatedAt(row)),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        className="ops-card"
        title="规则评估"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增规则
            </Button>
          </Space>
        }
      >
        <AntAlert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="本阶段只写入 alert_decisions，不创建 alerts，不触发通知。"
        />

        <Table<RuleRow>
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={ruleColumns}
          pagination={{ pageSize: 8 }}
          scroll={{ x: 720 }}
          locale={{ emptyText: '暂无规则' }}
        />
      </Card>

      <Card className="ops-card" title="手动执行规则评估">
        <Form layout="inline" form={evaluationForm} onFinish={runEvaluation}>
          <Form.Item
            name="standardEventId"
            label="standardEventId"
            rules={[{ required: true, message: '请输入 standardEventId' }]}
          >
            <InputNumber min={1} precision={0} style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="ruleId" label="ruleId">
            <Select
              allowClear
              showSearch
              options={ruleOptions}
              placeholder="全部启用规则"
              style={{ width: 240 }}
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="operatorName" label="operatorName">
            <Input placeholder="ops-user" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" icon={<PlayCircleOutlined />}>
              执行规则评估
            </Button>
          </Form.Item>
        </Form>
        <Typography.Text className="table-subtext">{evaluationSummary(lastResult)}</Typography.Text>
      </Card>

      <Card className="ops-card" title="最近评估结果">
        <Table<RuleEvaluationDecisionRow>
          rowKey={(row) => `${row.id ?? decisionStandardEventId(row)}-${decisionRuleId(row) ?? decisionRuleName(row)}`}
          loading={decisionLoading}
          dataSource={decisions}
          columns={decisionColumns}
          pagination={{ pageSize: 8 }}
          scroll={{ x: 980 }}
          locale={{ emptyText: '暂无评估结果' }}
        />
      </Card>

      <Modal title="新增规则" open={open} onOk={submit} onCancel={() => setOpen(false)} okText="保存" width={680}>
        <Form layout="vertical" form={form}>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：High risk file operation" />
          </Form.Item>

          <div className="rule-form-grid">
            <Form.Item name="eventType" label="event_type" rules={[{ required: true, message: '请选择 event_type' }]}>
              <Select options={eventTypeOptions} suffixIcon={<SafetyCertificateOutlined />} />
            </Form.Item>
            <Form.Item name="severity" label="decision severity" rules={[{ required: true, message: '请选择输出等级' }]}>
              <Select options={severityOptions} />
            </Form.Item>
          </div>

          <div className="rule-form-grid">
            <Form.Item name="minSeverity" label="minSeverity" rules={[{ required: true, message: '请选择 minSeverity' }]}>
              <Select options={severityOptions} />
            </Form.Item>
            <Form.Item name="threshold" label="riskScore 阈值" rules={[{ required: true, message: '请输入阈值' }]}>
              <InputNumber min={0} max={100} precision={0} addonBefore=">=" />
            </Form.Item>
          </div>

          <Form.Item name="enabled" label="启用规则" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
