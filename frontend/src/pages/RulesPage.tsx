import { PlusOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost, apiPut } from '../api';
import type { NotificationChannelRow, RuleRow, Severity } from '../types';

interface RuleFormValues {
  name: string;
  scenario: string;
  timeWindow: string;
  threshold: number;
  subjectScope: string;
  assetScope: string;
  notifyEnabled: boolean;
  channelIds: number[];
  severity: Severity;
  enabled: boolean;
}

interface ScenarioOption {
  value: string;
  label: string;
  eventType: string;
  defaultName: string;
  metricLabel: string;
  unit: string;
  defaultThreshold: number;
  description: string;
}

interface RuleExpressionModel {
  version: number;
  mode: 'template';
  scenario: string;
  timeWindow: string;
  threshold: {
    metric: string;
    label: string;
    value: number;
    unit: string;
  };
  scope: {
    subject: string;
    asset: string;
  };
  action: {
    createAlert: boolean;
    notify: boolean;
    channelIds: number[];
  };
}

const timeWindowOptions = [
  { value: 'all_day', label: '全天' },
  { value: 'work_hours', label: '工作时间' },
  { value: 'after_hours', label: '非工作时间' },
  { value: 'last_1h', label: '最近 1 小时' },
  { value: 'last_24h', label: '最近 24 小时' },
];

const subjectScopeOptions = [
  { value: 'all_users', label: '全部用户' },
  { value: 'privileged_users', label: '高权限用户' },
  { value: 'external_users', label: '外协/临时账号' },
  { value: 'custom_group', label: '指定用户组' },
];

const assetScopeOptions = [
  { value: 'all_assets', label: '全部资产' },
  { value: 'server_assets', label: '服务器资产' },
  { value: 'database_assets', label: '数据库资产' },
  { value: 'sensitive_assets', label: '敏感资产' },
];

const scenarioOptions: ScenarioOption[] = [
  {
    value: 'file_leakage',
    label: '疑似文件泄密',
    eventType: 'file_operation',
    defaultName: '疑似文件泄密预警',
    metricLabel: '文件外发/拷贝次数',
    unit: '次',
    defaultThreshold: 5,
    description: '同一用户在指定时间内出现多次文件外发、复制或异常传输。',
  },
  {
    value: 'large_file_transfer',
    label: '大文件外发',
    eventType: 'file_transfer',
    defaultName: '大文件外发预警',
    metricLabel: '单次文件大小',
    unit: 'MB',
    defaultThreshold: 100,
    description: '单次外发或传输文件体积超过业务设定阈值。',
  },
  {
    value: 'removable_storage',
    label: '移动存储拷贝',
    eventType: 'device_operation',
    defaultName: '移动存储拷贝预警',
    metricLabel: '拷贝文件数量',
    unit: '个',
    defaultThreshold: 20,
    description: '检测 U 盘、移动硬盘等外设上的批量拷贝行为。',
  },
  {
    value: 'sensitive_data_access',
    label: '敏感数据访问',
    eventType: 'data_access',
    defaultName: '敏感数据访问预警',
    metricLabel: '访问敏感对象次数',
    unit: '次',
    defaultThreshold: 10,
    description: '对敏感字段、敏感表、重要文件的高频访问。',
  },
  {
    value: 'abnormal_login',
    label: '异常登录行为',
    eventType: 'account_activity',
    defaultName: '异常登录行为预警',
    metricLabel: '异常登录次数',
    unit: '次',
    defaultThreshold: 3,
    description: '异常地点、异常时间、失败重试或高风险账号登录。',
  },
];

function scenarioByEventType(eventType: string) {
  return scenarioOptions.find((item) => item.eventType === eventType);
}

function scenarioByValue(value?: string) {
  return scenarioOptions.find((item) => item.value === value) ?? scenarioOptions[0];
}

function scenarioByValueStrict(value?: string) {
  return scenarioOptions.find((item) => item.value === value);
}

function labelByValue(options: Array<{ value: string; label: string }>, value?: string) {
  return options.find((item) => item.value === value)?.label ?? value ?? '-';
}

function metricKeyForScenario(value: string) {
  return {
    file_leakage: 'operation_count',
    large_file_transfer: 'file_size_mb',
    removable_storage: 'copied_files',
    sensitive_data_access: 'access_count',
    abnormal_login: 'abnormal_login_count',
  }[value] ?? 'event_count';
}

function buildRuleExpression(values: RuleFormValues, scenario: ScenarioOption) {
  const model: RuleExpressionModel = {
    version: 1,
    mode: 'template',
    scenario: scenario.value,
    timeWindow: values.timeWindow,
    threshold: {
      metric: metricKeyForScenario(scenario.value),
      label: scenario.metricLabel,
      value: values.threshold,
      unit: scenario.unit,
    },
    scope: {
      subject: values.subjectScope,
      asset: values.assetScope,
    },
    action: {
      createAlert: true,
      notify: values.notifyEnabled,
      channelIds: values.notifyEnabled ? values.channelIds ?? [] : [],
    },
  };
  return JSON.stringify(model);
}

function parseRuleExpression(expression: string): Partial<RuleExpressionModel> {
  try {
    const parsed = JSON.parse(expression) as RuleExpressionModel;
    if (parsed?.mode === 'template') {
      return parsed;
    }
  } catch {
    return parseLegacyExpression(expression);
  }
  return parseLegacyExpression(expression);
}

function parseLegacyExpression(expression: string): Partial<RuleExpressionModel> {
  const scenarioMatch = expression.match(/scenario == "([^"]+)"/);
  const timeWindowMatch = expression.match(/time_window == "([^"]+)"/);
  const thresholdMatch = expression.match(/>=\s*(\d+)/);
  return {
    scenario: scenarioMatch?.[1],
    timeWindow: timeWindowMatch?.[1],
    threshold: {
      metric: 'legacy',
      label: '触发阈值',
      value: thresholdMatch?.[1] ? Number(thresholdMatch[1]) : 0,
      unit: '',
    },
    scope: {
      subject: 'all_users',
      asset: 'all_assets',
    },
    action: {
      createAlert: true,
      notify: false,
      channelIds: [],
    },
  };
}

function severityColor(value: string) {
  return {
    critical: 'red',
    high: 'orange',
    medium: 'gold',
    low: 'green',
    info: 'cyan',
  }[value] ?? 'default';
}

function severityLabel(value: string) {
  return {
    critical: '严重',
    high: '高',
    medium: '中',
    low: '低',
    info: '提示',
  }[value] ?? value;
}

export default function RulesPage() {
  const [rows, setRows] = useState<RuleRow[]>([]);
  const [channels, setChannels] = useState<NotificationChannelRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<RuleFormValues>();
  const selectedScenarioValue = Form.useWatch('scenario', form);
  const notifyEnabled = Form.useWatch('notifyEnabled', form);
  const selectedScenario = useMemo(() => scenarioByValue(selectedScenarioValue), [selectedScenarioValue]);
  const channelOptions = useMemo(
    () =>
      channels
        .filter((channel) => channel.enabled)
        .map((channel) => ({
          value: channel.id,
          label: `${channel.name} (${channel.channel_type})`,
        })),
    [channels],
  );
  const channelNameMap = useMemo(
    () => new Map(channels.map((channel) => [channel.id, channel.name])),
    [channels],
  );

  async function load() {
    setLoading(true);
    try {
      const [ruleRows, channelRows] = await Promise.all([
        apiGet<RuleRow[]>('/api/alerts/rules'),
        apiGet<NotificationChannelRow[]>('/api/notifications/channels'),
      ]);
      setRows(ruleRows);
      setChannels(channelRows);
    } catch {
      setRows([]);
      setChannels([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function openCreate() {
    const scenario = scenarioOptions[0];
    form.setFieldsValue({
      name: scenario.defaultName,
      scenario: scenario.value,
      timeWindow: 'all_day',
      threshold: scenario.defaultThreshold,
      subjectScope: 'all_users',
      assetScope: 'all_assets',
      notifyEnabled: true,
      channelIds: [],
      severity: 'medium',
      enabled: true,
    });
    setOpen(true);
  }

  function handleScenarioChange(value: string) {
    const scenario = scenarioByValue(value);
    form.setFieldsValue({
      name: scenario.defaultName,
      threshold: scenario.defaultThreshold,
    });
  }

  async function submit() {
    const values = await form.validateFields();
    const scenario = scenarioByValue(values.scenario);
    await apiPost('/api/alerts/rules', {
      name: values.name,
      eventType: scenario.eventType,
      severity: values.severity,
      expression: buildRuleExpression(values, scenario),
      enabled: values.enabled,
    });
    message.success('规则已创建');
    setOpen(false);
    form.resetFields();
    await load();
  }

  async function toggleEnabled(row: RuleRow, enabled: boolean) {
    await apiPut(`/api/alerts/rules/${row.id}/enabled`, { enabled });
    message.success(enabled ? '规则已启用' : '规则已停用');
    await load();
  }

  const columns: ColumnsType<RuleRow> = [
    {
      title: '规则名称',
      dataIndex: 'name',
      render: (value: string, row) => {
        const model = parseRuleExpression(row.expression);
        const scenario = scenarioByValueStrict(model.scenario) ?? scenarioByEventType(row.event_type);
        return (
          <div>
            <strong>{value}</strong>
            <span className="table-subtext">{scenario?.label ?? row.event_type}</span>
          </div>
        );
      },
    },
    {
      title: '监控时间',
      dataIndex: 'expression',
      width: 140,
      render: (expression: string) => labelByValue(timeWindowOptions, parseRuleExpression(expression).timeWindow),
    },
    {
      title: '触发条件',
      dataIndex: 'expression',
      width: 190,
      render: (expression: string) => {
        const model = parseRuleExpression(expression);
        const threshold = model.threshold;
        if (!threshold || !threshold.value) {
          return '-';
        }
        return `${threshold.label} >= ${threshold.value}${threshold.unit}`;
      },
    },
    {
      title: '对象范围',
      dataIndex: 'expression',
      width: 180,
      render: (expression: string) => {
        const scope = parseRuleExpression(expression).scope;
        return (
          <div>
            <span>{labelByValue(subjectScopeOptions, scope?.subject)}</span>
            <span className="table-subtext">{labelByValue(assetScopeOptions, scope?.asset)}</span>
          </div>
        );
      },
    },
    {
      title: '动作',
      dataIndex: 'expression',
      width: 130,
      render: (expression: string) => {
        const action = parseRuleExpression(expression).action;
        const names = action?.channelIds?.map((id) => channelNameMap.get(id) ?? `#${id}`) ?? [];
        return (
          <Space size={4} wrap>
            <Tag color="blue">生成告警</Tag>
            {action?.notify && <Tag color="cyan">通知 {names.length ? names.length : '全部'}</Tag>}
          </Space>
        );
      },
    },
    {
      title: '等级',
      dataIndex: 'severity',
      width: 100,
      render: (value) => <Tag color={severityColor(value)}>{severityLabel(value)}</Tag>,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 90,
      render: (value, row) => <Switch size="small" checked={value} onChange={(checked) => toggleEnabled(row, checked)} />,
    },
  ];

  return (
    <Card
      className="ops-card"
      title="规则中心"
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
      <Table<RuleRow>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        scroll={{ x: 980 }}
        locale={{ emptyText: '暂无规则。可以通过风险场景模板快速创建。' }}
      />

      <Modal title="新增规则" open={open} onOk={submit} onCancel={() => setOpen(false)} okText="保存" width={720}>
        <Form layout="vertical" form={form}>
          <Form.Item name="scenario" label="风险场景" rules={[{ required: true, message: '请选择风险场景' }]}>
            <Select
              options={scenarioOptions.map((item) => ({ value: item.value, label: item.label }))}
              onChange={handleScenarioChange}
              suffixIcon={<SafetyCertificateOutlined />}
            />
          </Form.Item>

          <Typography.Paragraph className="rule-template-note">{selectedScenario.description}</Typography.Paragraph>

          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：疑似文件泄密预警" />
          </Form.Item>

          <div className="rule-form-grid">
            <Form.Item name="timeWindow" label="监控时间" rules={[{ required: true, message: '请选择监控时间' }]}>
              <Select options={timeWindowOptions} />
            </Form.Item>
            <Form.Item
              name="threshold"
              label={`${selectedScenario.metricLabel}阈值`}
              rules={[{ required: true, message: '请输入阈值' }]}
            >
              <InputNumber min={1} addonAfter={selectedScenario.unit} />
            </Form.Item>
          </div>

          <div className="rule-form-grid">
            <Form.Item name="subjectScope" label="用户范围" rules={[{ required: true, message: '请选择用户范围' }]}>
              <Select options={subjectScopeOptions} />
            </Form.Item>
            <Form.Item name="assetScope" label="资产范围" rules={[{ required: true, message: '请选择资产范围' }]}>
              <Select options={assetScopeOptions} />
            </Form.Item>
          </div>

          <div className="rule-form-grid">
            <Form.Item name="severity" label="风险等级">
              <Select
                options={[
                  { value: 'critical', label: '严重' },
                  { value: 'high', label: '高' },
                  { value: 'medium', label: '中' },
                  { value: 'low', label: '低' },
                  { value: 'info', label: '提示' },
                ]}
              />
            </Form.Item>
            <Form.Item name="notifyEnabled" label="通知动作" valuePropName="checked">
              <Switch checkedChildren="发送" unCheckedChildren="不发送" />
            </Form.Item>
          </div>

          {notifyEnabled && (
            <Form.Item
              name="channelIds"
              label="通知通道"
              extra="未选择通道时，规则触发后默认推送到所有启用通道。"
            >
              <Select
                mode="multiple"
                allowClear
                options={channelOptions}
                placeholder={channelOptions.length ? '选择要推送的通道' : '暂无启用的通知通道'}
                disabled={!channelOptions.length}
              />
            </Form.Item>
          )}

          <Form.Item name="enabled" label="启用规则" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
