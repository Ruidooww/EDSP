import { CheckCircleOutlined, CloseCircleOutlined, EyeOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Drawer, Empty, Input, List, Select, Space, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useState } from 'react';
import { apiGet, apiPost, apiPut } from '../api';
import type { AlertNoteRow, AlertRow } from '../types';

interface IngestResult {
  id: number;
  action: 'created' | 'updated';
  ruleExecution?: {
    matched: number;
    notifications: Array<{
      total?: number;
      success?: number;
      failed?: number;
    }>;
  };
}

function severityLabel(value: string) {
  return {
    critical: '严重',
    high: '高危',
    medium: '中危',
    low: '低危',
    info: '提示',
  }[value] ?? value;
}

function severityColor(value: string) {
  return {
    critical: 'red',
    high: 'red',
    medium: 'orange',
    low: 'gold',
    info: 'cyan',
  }[value] ?? 'default';
}

function statusLabel(value: string) {
  return {
    open: '未处理',
    processing: '处理中',
    resolved: '已恢复',
    closed: '已关闭',
  }[value] ?? value;
}

function statusColor(value: string) {
  return {
    open: 'warning',
    processing: 'processing',
    resolved: 'success',
    closed: 'default',
  }[value] ?? 'default';
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

function detailText(value?: AlertRow['detail_json']) {
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

export default function AlertsPage() {
  const [rows, setRows] = useState<AlertRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [activeRow, setActiveRow] = useState<AlertRow | null>(null);
  const [notes, setNotes] = useState<AlertNoteRow[]>([]);
  const [notesLoading, setNotesLoading] = useState(false);
  const [noteSaving, setNoteSaving] = useState(false);
  const [noteText, setNoteText] = useState('');
  const [noteStatus, setNoteStatus] = useState<string | undefined>();

  async function load() {
    setLoading(true);
    try {
      setRows(await apiGet<AlertRow[]>('/api/alerts'));
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    if (!activeRow) {
      setNotes([]);
      setNoteText('');
      setNoteStatus(undefined);
      return;
    }
    setNoteStatus(activeRow.status);
    void loadNotes(activeRow.id);
  }, [activeRow?.id]);

  async function loadNotes(alertId: number) {
    setNotesLoading(true);
    try {
      setNotes(await apiGet<AlertNoteRow[]>(`/api/alerts/${alertId}/notes`));
    } catch {
      setNotes([]);
    } finally {
      setNotesLoading(false);
    }
  }

  async function sendSampleAlert() {
    const payload = {
      sourceSystem: 'demo-api',
      externalId: `demo-${Date.now()}`,
      alertType: 'data_access',
      title: '样例：敏感字段访问风险',
      severity: 'medium',
      occurredAt: new Date().toISOString(),
      actor: 'zhangsan',
      asset: 'db-prod-01',
      policyName: '敏感字段访问策略',
      subjectType: 'database',
      subjectRef: 'customer.phone',
      status: 'open',
      detail: {
        channel: 'api',
        access_count: 12,
        subjectScope: 'all_users',
        assetScope: 'database_assets',
        description: '这是一条用于验证通用告警接入接口的样例数据。',
      },
    };
    const result = await apiPost<IngestResult>('/api/alerts/ingest', payload);
    const matched = result.ruleExecution?.matched ?? 0;
    const sent = result.ruleExecution?.notifications.reduce((sum, item) => sum + (item.total ?? 0), 0) ?? 0;
    message.success(
      `样例告警已${result.action === 'updated' ? '更新' : '创建'}，ID：${result.id}，命中规则 ${matched} 条，通知 ${sent} 次`,
    );
    await load();
  }

  async function updateStatus(row: AlertRow, status: 'processing' | 'resolved' | 'closed') {
    await apiPut(`/api/alerts/${row.id}/status`, { status });
    message.success(`告警已更新为：${statusLabel(status)}`);
    await load();
  }

  async function submitNote() {
    if (!activeRow) {
      return;
    }
    const note = noteText.trim();
    if (!note) {
      message.warning('请输入处理意见');
      return;
    }
    setNoteSaving(true);
    try {
      await apiPost(`/api/alerts/${activeRow.id}/notes`, {
        operatorName: 'admin',
        note,
        status: noteStatus,
      });
      message.success('处置记录已保存');
      setNoteText('');
      await loadNotes(activeRow.id);
      await load();
      if (noteStatus) {
        setActiveRow({ ...activeRow, status: noteStatus });
      }
    } finally {
      setNoteSaving(false);
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
            {row.source_system || 'unknown'} / {row.alert_type || 'generic'}
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
    { title: '用户', dataIndex: 'actor', width: 120, render: (value) => value || '-' },
    { title: '资产', dataIndex: 'asset_ref', width: 140, render: (value) => value || '-' },
    { title: '策略', dataIndex: 'policy_name', width: 160, render: (value) => value || '-' },
    {
      title: '对象',
      dataIndex: 'subject_ref',
      render: (value: string, row) => (
        <div>
          <span>{value || '-'}</span>
          <span className="table-subtext">{row.subject_type || '-'}</span>
        </div>
      ),
    },
    { title: '发生时间', dataIndex: 'occurred_at', width: 150, render: formatTime },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => <Tag color={statusColor(value)}>{statusLabel(value)}</Tag>,
    },
    {
      title: '操作',
      width: 240,
      fixed: 'right',
      render: (_, row) => (
        <Space size={6}>
          <Button size="small" icon={<EyeOutlined />} onClick={() => setActiveRow(row)}>
            详情
          </Button>
          {row.status === 'open' && (
            <Button size="small" onClick={() => updateStatus(row, 'processing')}>
              处理
            </Button>
          )}
          {row.status !== 'resolved' && row.status !== 'closed' && (
            <Button size="small" icon={<CheckCircleOutlined />} onClick={() => updateStatus(row, 'resolved')}>
              确认
            </Button>
          )}
          {row.status !== 'closed' && (
            <Button size="small" danger icon={<CloseCircleOutlined />} onClick={() => updateStatus(row, 'closed')}>
              关闭
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="alerts-page">
      <Card
        className="dashboard-card"
        title="告警中心"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={sendSampleAlert}>
              发送样例告警
            </Button>
          </Space>
        }
      >
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="这里展示平台标准告警模型，来源可以是 API、文件导入、数据库采集、Webhook 或第三方安全平台。"
        />
        <Descriptions className="ingest-doc" size="small" bordered column={1}>
          <Descriptions.Item label="标准接入接口">
            <Typography.Text code>
              POST /api/alerts/ingest
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="核心字段">
            sourceSystem、externalId、alertType、title、severity、occurredAt、actor、asset、policyName、subjectType、subjectRef、detail
          </Descriptions.Item>
        </Descriptions>
        <Table<AlertRow>
          className="alerts-table"
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1180 }}
          locale={{ emptyText: '暂无告警。可以先点击“发送样例告警”验证通用接入链路。' }}
        />
      </Card>
      <Drawer
        title="告警详情"
        width={720}
        open={Boolean(activeRow)}
        onClose={() => setActiveRow(null)}
        destroyOnClose
      >
        {activeRow && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="标题">{activeRow.title}</Descriptions.Item>
              <Descriptions.Item label="等级">
                <Tag color={severityColor(activeRow.severity)}>{severityLabel(activeRow.severity)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor(activeRow.status)}>{statusLabel(activeRow.status)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="来源">
                {activeRow.source_system || '-'} / {activeRow.external_id || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="事件类型">{activeRow.alert_type || '-'}</Descriptions.Item>
              <Descriptions.Item label="主体">
                {activeRow.subject_type || '-'} / {activeRow.subject_ref || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="用户">{activeRow.actor || '-'}</Descriptions.Item>
              <Descriptions.Item label="资产">{activeRow.asset_ref || '-'}</Descriptions.Item>
              <Descriptions.Item label="策略">{activeRow.policy_name || '-'}</Descriptions.Item>
              <Descriptions.Item label="发生时间">{formatTime(activeRow.occurred_at)}</Descriptions.Item>
            </Descriptions>
            <Typography.Paragraph className="json-preview">
              <pre>{detailText(activeRow.detail_json)}</pre>
            </Typography.Paragraph>
            <div className="disposition-panel">
              <Typography.Title level={5} className="section-subtitle">
                处置记录
              </Typography.Title>
              <Space.Compact block className="disposition-status-row">
                <Select
                  value={noteStatus}
                  onChange={setNoteStatus}
                  options={[
                    { value: 'open', label: '未处理' },
                    { value: 'processing', label: '处理中' },
                    { value: 'resolved', label: '已确认' },
                    { value: 'closed', label: '已关闭' },
                  ]}
                />
                <Button type="primary" loading={noteSaving} onClick={submitNote}>
                  保存处置
                </Button>
              </Space.Compact>
              <Input.TextArea
                rows={4}
                value={noteText}
                onChange={(event) => setNoteText(event.target.value)}
                placeholder="记录处理意见、误报原因、处置动作或处置建议"
              />
              <List<AlertNoteRow>
                className="disposition-list"
                loading={notesLoading}
                dataSource={notes}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无处置记录" /> }}
                renderItem={(item) => (
                  <List.Item>
                    <List.Item.Meta
                      title={
                        <Space>
                          <span>{item.operator_name}</span>
                          <Typography.Text type="secondary">{formatTime(item.created_at)}</Typography.Text>
                        </Space>
                      }
                      description={item.note}
                    />
                  </List.Item>
                )}
              />
            </div>
          </Space>
        )}
      </Drawer>
    </div>
  );
}
