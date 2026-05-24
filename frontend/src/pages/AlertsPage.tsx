import { BellOutlined, EyeOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Drawer, Empty, Form, InputNumber, Space, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../api';
import type { AlertGenerationRunResult, AlertRow } from '../types';

function severityLabel(value?: string) {
  return {
    critical: '严重',
    high: '高危',
    medium: '中危',
    low: '低危',
    info: '提示',
  }[value ?? ''] ?? (value || '-');
}

function severityColor(value?: string) {
  return {
    critical: 'red',
    high: 'red',
    medium: 'orange',
    low: 'gold',
    info: 'cyan',
  }[value ?? ''] ?? 'default';
}

function statusLabel(value?: string) {
  return {
    open: '开放',
    processing: '处理中',
    resolved: '已确认',
    closed: '已关闭',
  }[value ?? ''] ?? (value || '-');
}

function statusColor(value?: string) {
  return {
    open: 'warning',
    processing: 'processing',
    resolved: 'success',
    closed: 'default',
  }[value ?? ''] ?? 'default';
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

function detailText(value?: AlertRow['detail_json'] | AlertRow['detail']) {
  if (!value) {
    return '{}';
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return JSON.stringify(value, null, 2);
}

function alertId(row: AlertRow) {
  return row.id;
}

function decisionId(row: AlertRow) {
  return row.alertDecisionId ?? row.alert_decision_id ?? row.decisionId;
}

function standardEventId(row: AlertRow) {
  return row.standardEventId ?? row.standard_event_id;
}

function ruleName(row: AlertRow) {
  return row.ruleName ?? row.rule_name ?? row.policy_name ?? '-';
}

function ruleId(row: AlertRow) {
  return row.ruleId ?? row.rule_id;
}

export default function AlertsPage() {
  const [rows, setRows] = useState<AlertRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [activeRow, setActiveRow] = useState<AlertRow | null>(null);
  const [form] = Form.useForm<{ decisionId: number }>();

  async function load() {
    setLoading(true);
    try {
      setRows(await apiGet<AlertRow[]>('/api/core/alerts?limit=50'));
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function generateAlert(values: { decisionId: number }) {
    setGenerating(true);
    try {
      const result = await apiPost<AlertGenerationRunResult>('/api/core/alert-generations/run', {
        decisionId: values.decisionId,
      });
      const action = result.action === 'existing' ? '已存在' : '已创建';
      message.success(`告警${action}，ID：${result.id ?? '-'}，decisionId：${result.decisionId ?? values.decisionId}`);
      form.resetFields();
      await load();
    } finally {
      setGenerating(false);
    }
  }

  const columns: ColumnsType<AlertRow> = [
    {
      title: '告警',
      dataIndex: 'title',
      render: (title: string, row) => (
        <div>
          <strong>{title}</strong>
          <span className="table-subtext">
            {row.sourceSystem || row.source_system || 'unknown'} / {row.alertType || row.alert_type || 'generic'}
          </span>
        </div>
      ),
    },
    {
      title: '等级',
      dataIndex: 'severity',
      width: 90,
      render: (value: string) => <Tag color={severityColor(value)}>{severityLabel(value)}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: string) => <Tag color={statusColor(value)}>{statusLabel(value)}</Tag>,
    },
    { title: '规则', width: 180, render: (_, row) => ruleName(row) },
    { title: 'Decision ID', width: 120, render: (_, row) => decisionId(row) ?? '-' },
    { title: 'Standard Event', width: 140, render: (_, row) => standardEventId(row) ?? '-' },
    { title: '用户', dataIndex: 'actor', width: 120, render: (value) => value || '-' },
    {
      title: '资产',
      width: 140,
      render: (_, row) => row.assetRef || row.asset_ref || '-',
    },
    {
      title: '发生时间',
      width: 150,
      render: (_, row) => formatTime(row.occurredAt || row.occurred_at),
    },
    {
      title: '操作',
      width: 100,
      fixed: 'right',
      render: (_, row) => (
        <Button size="small" icon={<EyeOutlined />} onClick={() => setActiveRow(row)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <div className="alerts-page">
      <Card
        className="dashboard-card"
        title="告警中心"
        extra={
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            刷新
          </Button>
        }
      >
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="本阶段只从 matched alert_decisions 创建 alerts，不发送通知。"
        />
        <Card size="small" className="section-card" title="手动生成告警">
          <Form form={form} layout="inline" onFinish={generateAlert}>
            <Form.Item
              name="decisionId"
              label="Decision ID"
              rules={[{ required: true, message: '请输入 matched alert_decision 的 ID' }]}
            >
              <InputNumber min={1} precision={0} placeholder="123" />
            </Form.Item>
            <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={generating}>
              从决策生成告警
            </Button>
          </Form>
        </Card>
        <Table<AlertRow>
          className="alerts-table"
          rowKey={alertId}
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1180 }}
          locale={{ emptyText: '暂无告警。可先在规则评估页生成 matched alert_decisions，再按 Decision ID 手动生成告警。' }}
        />
      </Card>
      <Drawer
        title="告警详情"
        width={720}
        open={Boolean(activeRow)}
        onClose={() => setActiveRow(null)}
        destroyOnClose
      >
        {activeRow ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Typography.Title level={5}>{activeRow.title}</Typography.Title>
            <Space wrap>
              <Tag color={severityColor(activeRow.severity)}>{severityLabel(activeRow.severity)}</Tag>
              <Tag color={statusColor(activeRow.status)}>{statusLabel(activeRow.status)}</Tag>
            </Space>
            <div className="detail-grid">
              <span>Decision ID</span>
              <strong>{decisionId(activeRow) ?? '-'}</strong>
              <span>Standard Event</span>
              <strong>{standardEventId(activeRow) ?? '-'}</strong>
              <span>Rule</span>
              <strong>{ruleName(activeRow)}</strong>
              <span>Rule ID</span>
              <strong>{ruleId(activeRow) ?? '-'}</strong>
              <span>Source</span>
              <strong>{activeRow.sourceSystem || activeRow.source_system || '-'}</strong>
              <span>External ID</span>
              <strong>{activeRow.externalId || activeRow.external_id || '-'}</strong>
              <span>Event Type</span>
              <strong>{activeRow.alertType || activeRow.alert_type || '-'}</strong>
              <span>Actor</span>
              <strong>{activeRow.actor || '-'}</strong>
              <span>Asset</span>
              <strong>{activeRow.assetRef || activeRow.asset_ref || '-'}</strong>
              <span>Subject</span>
              <strong>{activeRow.subjectRef || activeRow.subject_ref || '-'}</strong>
              <span>Occurred At</span>
              <strong>{formatTime(activeRow.occurredAt || activeRow.occurred_at)}</strong>
            </div>
            <Typography.Paragraph className="json-preview">
              <pre>{detailText(activeRow.detail || activeRow.detail_json)}</pre>
            </Typography.Paragraph>
          </Space>
        ) : (
          <Empty />
        )}
      </Drawer>
    </div>
  );
}
