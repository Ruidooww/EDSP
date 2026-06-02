import { PlayCircleOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import {
  Alert as AntAlert,
  Button,
  Card,
  Collapse,
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
import AdvancedDetailsCollapse from '../components/AdvancedDetailsCollapse';
import NextStepHint from '../components/NextStepHint';
import type {
  RuleEvaluationDecisionRow,
  RuleEvaluationRunRequest,
  RuleEvaluationRunResult,
  RuleRequest,
  RuleRow,
  Severity,
} from '../types';
import {
  formatBusinessTime,
  getEventTypeLabel,
  getRuleDecisionColor,
  getRuleDecisionLabel,
  getRuleScenarioLabel,
  getSeverityColor,
  getSeverityLabel,
} from '../utils/businessDisplay';

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
  { value: 'file_operation', label: getEventTypeLabel('file_operation') },
  { value: 'file_transfer', label: getEventTypeLabel('file_transfer') },
  { value: 'device_operation', label: getEventTypeLabel('device_operation') },
  { value: 'data_access', label: getEventTypeLabel('data_access') },
  { value: 'account_activity', label: getEventTypeLabel('account_activity') },
];

const severityOptions: Array<{ value: Severity; label: string }> = [
  { value: 'critical', label: getSeverityLabel('critical') },
  { value: 'high', label: getSeverityLabel('high') },
  { value: 'medium', label: getSeverityLabel('medium') },
  { value: 'low', label: getSeverityLabel('low') },
  { value: 'info', label: getSeverityLabel('info') },
];

function severityColor(value?: string) {
  return getSeverityColor(value);
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
  return row.ruleName ?? row.rule_name ?? '规则待确认';
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
  return formatBusinessTime(value);
}

function evaluationSummary(result?: RuleEvaluationRunResult) {
  if (!result) {
    return '尚未执行';
  }
  return `已评估 ${result.evaluatedCount} 条事件，命中 ${result.matchedCount ?? 0} 条，未命中 ${
    result.notMatchedCount ?? 0
  } 条，跳过 ${result.skippedCount ?? 0} 条，异常 ${result.errorCount ?? 0} 条`;
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
        .map((row) => ({ value: row.id, label: row.name })),
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
          <span className="table-subtext">{getEventTypeLabel(ruleEventType(row))}</span>
        </div>
      ),
    },
    {
      title: '适用场景',
      dataIndex: 'expression',
      width: 260,
      render: (_, row) => getRuleScenarioLabel(row),
    },
    {
      title: '风险等级',
      dataIndex: 'severity',
      width: 110,
      render: (value) => <Tag color={severityColor(value)}>{getSeverityLabel(value)}</Tag>,
    },
    {
      title: '启用状态',
      dataIndex: 'enabled',
      width: 160,
      render: (value, row) => (
        <Space direction="vertical" size={2}>
          <Switch size="small" checked={value} onChange={(checked) => toggleEnabled(row, checked)} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {value ? '参与自动规则决策' : '暂不参与自动规则决策'}
          </Typography.Text>
        </Space>
      ),
    },
  ];

  const decisionColumns: ColumnsType<RuleEvaluationDecisionRow> = [
    {
      title: '规则',
      render: (_, row) => decisionRuleName(row),
    },
    {
      title: '评估结果',
      width: 130,
      dataIndex: 'decision',
      render: (value: string) => <Tag color={getRuleDecisionColor(value)}>{getRuleDecisionLabel(value)}</Tag>,
    },
    {
      title: '原因',
      dataIndex: 'reason',
      width: 190,
      render: (value?: string) => value ?? '-',
    },
    {
      title: '风险等级',
      dataIndex: 'severity',
      width: 110,
      render: (value?: string) => <Tag color={severityColor(value)}>{getSeverityLabel(value)}</Tag>,
    },
    {
      title: '风险评分',
      width: 110,
      render: (_, row) => decisionRiskScore(row) ?? '-',
    },
    {
      title: '生成时间',
      width: 190,
      render: (_, row) => formatDate(decisionCreatedAt(row)),
    },
    {
      title: '技术详情',
      width: 160,
      render: (_, row) => (
        <AdvancedDetailsCollapse
          title="技术详情"
          items={[
            { label: 'standardEventId', value: decisionStandardEventId(row), code: true },
            { label: 'ruleId', value: decisionRuleId(row), code: true },
            { label: 'decision', value: row.decision, code: true },
            { label: 'riskScore', value: decisionRiskScore(row), code: true },
            { label: 'detail JSON', value: row.detail ?? row.detailJson ?? row.detail_json, code: true },
          ]}
        />
      ),
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
          message="风险规则用于判断标准化事件是否达到告警条件。规则评估不会直接发送通知。"
        />
        <NextStepHint description="优先确认高危规则是否启用；如需排查单条标准化事件，可展开下方高级工具补充评估。" />

        <Table<RuleRow>
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={ruleColumns}
          pagination={{ pageSize: 8 }}
          scroll={{ x: 820 }}
          expandable={{
            expandedRowRender: (row) => {
              const model = parseRuleExpression(row.expression);
              return (
                <AdvancedDetailsCollapse
                  title="高级规则条件"
                  items={[
                    { label: 'expression', value: row.expression, code: true },
                    { label: 'ruleId', value: row.id, code: true },
                    { label: 'riskScoreThreshold', value: model.threshold?.value, code: true },
                    { label: 'minSeverity', value: model.minSeverity, code: true },
                    { label: 'eventType', value: ruleEventType(row), code: true },
                  ]}
                />
              );
            },
            rowExpandable: () => true,
          }}
          locale={{ emptyText: '暂无规则。请先新增风险规则，或等待系统导入规则模板。' }}
        />
      </Card>

      <Collapse
        className="advanced-details-collapse"
        items={[
          {
            key: 'manual-rule-evaluation',
            label: '高级工具：立即执行规则评估',
            children: (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                <Typography.Text type="secondary">
                  用于运营管理员对指定标准事件进行补评估。普通告警处理无需使用该工具。
                </Typography.Text>
                <Form layout="inline" form={evaluationForm} onFinish={runEvaluation} initialValues={{ operatorName: 'admin' }}>
                  <Form.Item
                    name="standardEventId"
                    label="标准化事件编号（高级）"
                    rules={[{ required: true, message: '请输入标准化事件编号' }]}
                  >
                    <InputNumber min={1} precision={0} style={{ width: 220 }} placeholder="例如：123，可从技术详情复制" />
                  </Form.Item>
                  <Form.Item name="ruleId" label="规则范围">
                    <Select
                      allowClear
                      showSearch
                      options={ruleOptions}
                      placeholder="全部启用规则"
                      style={{ width: 240 }}
                      optionFilterProp="label"
                    />
                  </Form.Item>
                  <Form.Item name="operatorName" hidden>
                    <Input />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" htmlType="submit" icon={<PlayCircleOutlined />}>
                      执行规则评估
                    </Button>
                  </Form.Item>
                </Form>
                <Typography.Text className="table-subtext">{evaluationSummary(lastResult)}</Typography.Text>
              </Space>
            ),
          },
        ]}
      />

      <Card className="ops-card" title="最近评估结果">
        <Table<RuleEvaluationDecisionRow>
          rowKey={(row) => `${row.id ?? decisionStandardEventId(row)}-${decisionRuleId(row) ?? decisionRuleName(row)}`}
          loading={decisionLoading}
          dataSource={decisions}
          columns={decisionColumns}
          pagination={{ pageSize: 8 }}
          scroll={{ x: 1080 }}
          locale={{ emptyText: '暂无评估结果。规则命中后会在这里显示最近决策结果。' }}
        />
      </Card>

      <Modal title="新增规则" open={open} onOk={submit} onCancel={() => setOpen(false)} okText="保存" width={680}>
        <Form layout="vertical" form={form}>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：敏感文件外发风险" />
          </Form.Item>

          <div className="rule-form-grid">
            <Form.Item name="eventType" label="适用事件类型" rules={[{ required: true, message: '请选择事件类型' }]}>
              <Select options={eventTypeOptions} suffixIcon={<SafetyCertificateOutlined />} />
            </Form.Item>
            <Form.Item name="severity" label="输出风险等级" rules={[{ required: true, message: '请选择输出等级' }]}>
              <Select options={severityOptions} />
            </Form.Item>
          </div>

          <div className="rule-form-grid">
            <Form.Item name="minSeverity" label="最低事件等级" rules={[{ required: true, message: '请选择最低事件等级' }]}>
              <Select options={severityOptions} />
            </Form.Item>
            <Form.Item name="threshold" label="风险评分阈值" rules={[{ required: true, message: '请输入阈值' }]}>
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
