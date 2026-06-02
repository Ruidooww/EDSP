import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Drawer, Form, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import type { ColumnsType } from 'antd/es/table';
import { apiGet } from '../api';
import AdvancedDetailsCollapse from '../components/AdvancedDetailsCollapse';
import BusinessStatusTag from '../components/BusinessStatusTag';
import type { AiAgentRunHistoryDetail, AiAgentRunHistoryRow } from '../types';
import {
  formatBusinessTime,
  getAiRunStatus,
  getPeriodLabel,
  getProviderLabel,
  getSourceLabel,
  getThemeLabel,
} from '../utils/businessDisplay';

const statusOptions = [
  { value: 'passed', label: '已生成' },
  { value: 'warning', label: '降级生成' },
  { value: 'failed', label: '生成失败' },
  { value: 'running', label: '生成中' },
];

const sourceOptions = [
  { value: 'llm', label: getSourceLabel('llm') },
  { value: 'fallback-template', label: getSourceLabel('fallback-template') },
];

const providerOptions = [
  'auto',
  'local-openai-compatible',
  'cloud-openai-compatible',
  'local-ollama-compatible',
  'fallback-template',
].map((value) => ({ value, label: getProviderLabel(value) }));

const themeOptions = [
  'security_overview',
  'high_risk_alerts',
  'rule_effectiveness',
  'sync_pipeline_health',
  'notification_readiness',
].map((value) => ({ value, label: getThemeLabel(value) }));

const periodOptions = [
  'last_24h',
  'last_7_days',
  'last_30_days',
].map((value) => ({ value, label: getPeriodLabel(value) }));

function formatDuration(durationMs?: number | null) {
  if (durationMs === undefined || durationMs === null) {
    return '-';
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  return `${(durationMs / 1000).toFixed(1)} s`;
}

export default function AiAgentRunHistoryPage() {
  const [form] = Form.useForm();
  const [rows, setRows] = useState<AiAgentRunHistoryRow[]>([]);
  const [selected, setSelected] = useState<AiAgentRunHistoryDetail>();
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);

  async function load(values = form.getFieldsValue()) {
    setLoading(true);
    try {
      const query = new URLSearchParams({ limit: '100' });
      Object.entries(values).forEach(([key, value]) => {
        if (typeof value === 'string' && value) {
          query.set(key, value);
        }
      });
      setRows(await apiGet<AiAgentRunHistoryRow[]>(`/api/core/ai-agents/runs?${query.toString()}`));
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function openDetail(id: number) {
    setDetailLoading(true);
    try {
      setSelected(await apiGet<AiAgentRunHistoryDetail>(`/api/core/ai-agents/runs/${id}`));
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setDetailLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const columns: ColumnsType<AiAgentRunHistoryRow> = [
    { title: '运行编号', dataIndex: 'id', width: 94, render: (value) => `#${value}` },
    { title: '分析模型', dataIndex: 'providerKey', render: (value) => getProviderLabel(value) },
    { title: '分析主题', dataIndex: 'theme', render: getThemeLabel },
    { title: '时间范围', dataIndex: 'period', render: getPeriodLabel },
    { title: '生成方式', dataIndex: 'source', render: getSourceLabel },
    {
      title: '状态',
      dataIndex: 'status',
      render: (value) => <BusinessStatusTag status={getAiRunStatus(value)} />,
    },
    { title: '建议章节', dataIndex: 'sectionCount', width: 88 },
    { title: '提醒', dataIndex: 'warningCount', width: 72 },
    { title: '开始时间', dataIndex: 'startedAt', render: formatBusinessTime },
    { title: '耗时', dataIndex: 'durationMs', width: 84, render: formatDuration },
  ];

  return (
    <div className="ai-agent-history-page">
      <div className="ops-heading">
        <div>
          <Typography.Title level={3}>AI 分析记录</Typography.Title>
          <Typography.Text type="secondary">
            查看智能分析的运行状态、生成方式和安全摘要。运行记录不展示原始提示词、模型回复正文或接入密钥。
          </Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>

      <Alert
        type="info"
        showIcon
        message="历史记录只包含可安全展示的运行摘要"
        description="详情仅展示聚合指标名称、建议章节标题和安全提醒，不包含敏感明细、接入地址或密钥。"
      />

      <Card className="ops-card ai-agent-run-panel">
        <Form form={form} layout="inline" onFinish={load}>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 132 }} options={statusOptions} /></Form.Item>
          <Form.Item name="source" label="生成方式"><Select allowClear style={{ width: 150 }} options={sourceOptions} /></Form.Item>
          <Form.Item name="providerKey" label="分析模型"><Select allowClear style={{ width: 190 }} options={providerOptions} /></Form.Item>
          <Form.Item name="theme" label="分析主题"><Select allowClear style={{ width: 160 }} options={themeOptions} /></Form.Item>
          <Form.Item name="period" label="时间范围"><Select allowClear style={{ width: 140 }} options={periodOptions} /></Form.Item>
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>筛选</Button>
        </Form>
      </Card>

      <Card className="ops-card">
        <Table<AiAgentRunHistoryRow>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1180 }}
          pagination={{ pageSize: 12 }}
          onRow={(row) => ({ onClick: () => void openDetail(row.id), style: { cursor: 'pointer' } })}
          locale={{ emptyText: '暂无符合条件的 AI 分析记录。' }}
        />
      </Card>

      <Drawer
        title={selected ? `AI 分析记录 #${selected.id}` : 'AI 分析记录'}
        width={620}
        open={Boolean(selected)}
        loading={detailLoading}
        onClose={() => setSelected(undefined)}
      >
        {selected ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Alert type="success" showIcon message="敏感字段已排除，仅展示安全摘要。" />
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="状态"><BusinessStatusTag status={getAiRunStatus(selected.status)} /></Descriptions.Item>
              <Descriptions.Item label="生成方式">{getSourceLabel(selected.source)}</Descriptions.Item>
              <Descriptions.Item label="分析模型">{getProviderLabel(selected.providerKey)}</Descriptions.Item>
              <Descriptions.Item label="分析主题">{getThemeLabel(selected.theme)}</Descriptions.Item>
              <Descriptions.Item label="时间范围">{getPeriodLabel(selected.period)}</Descriptions.Item>
              <Descriptions.Item label="开始时间">{formatBusinessTime(selected.startedAt)}</Descriptions.Item>
              <Descriptions.Item label="完成时间">{formatBusinessTime(selected.finishedAt)}</Descriptions.Item>
              <Descriptions.Item label="耗时">{formatDuration(selected.durationMs)}</Descriptions.Item>
              <Descriptions.Item label="建议章节">
                <Space wrap>
                  {selected.outputSummary.titles.length > 0
                    ? selected.outputSummary.titles.map((title) => <Tag key={title}>{title}</Tag>)
                    : '暂无章节标题'}
                </Space>
              </Descriptions.Item>
            </Descriptions>
            <AdvancedDetailsCollapse
              title="安全摘要技术详情"
              note="以下信息已按白名单过滤，可用于排障。"
              items={[
                { label: 'runId', value: selected.id, code: true },
                { label: 'agentKey', value: selected.agentKey, code: true },
                { label: 'providerKey', value: selected.providerKey, code: true },
                { label: 'modelName', value: selected.modelName, code: true },
                { label: 'metricKeys', value: selected.inputSummary.metricKeys, code: true },
                { label: 'sensitiveFieldsExcluded', value: selected.inputSummary.sensitiveFieldsExcluded, code: true },
                { label: 'sectionCount', value: selected.outputSummary.sectionCount, code: true },
                { label: 'warnings', value: selected.warnings, code: true },
                { label: 'errorCode', value: selected.errorCode, code: true },
              ]}
            />
          </Space>
        ) : null}
      </Drawer>
    </div>
  );
}
