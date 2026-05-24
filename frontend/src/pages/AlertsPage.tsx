import {
  BellOutlined,
  CheckOutlined,
  CloseCircleOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  UserSwitchOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../api';
import type {
  AlertGenerationRunResult,
  AlertLifecycleAction,
  AlertLifecycleRequest,
  AlertRow,
  AlertTimelineRow,
  NotificationAlertSendRequest,
  NotificationAlertSendResult,
  NotificationChannelRow,
  NotificationDeliveryRow,
} from '../types';

interface AlertNotificationFormValues {
  channelId: number;
}

interface AlertLifecycleFormValues {
  operatorName: string;
  assignee?: string;
  note?: string;
}

type AlertStatusFilter = 'all' | 'open' | 'acknowledged' | 'closed';

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
    acknowledged: '已确认',
    processing: '处理中',
    resolved: '已确认',
    closed: '已关闭',
  }[value ?? ''] ?? (value || '-');
}

function statusColor(value?: string) {
  return {
    open: 'warning',
    acknowledged: 'success',
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

function assignedTo(row: AlertRow) {
  return row.assignedTo ?? row.assigned_to;
}

function updatedAt(row: AlertRow) {
  return row.updatedAt ?? row.updated_at;
}

function channelType(row: NotificationChannelRow) {
  return row.channelType ?? row.channel_type;
}

function notificationChannelTypeLabel(value?: string) {
  return {
    webhook: 'Webhook',
    wecom: '企业微信',
    feishu: '飞书',
  }[value ?? ''] ?? (value || '-');
}

function timelineAction(row: AlertTimelineRow) {
  return row.action ?? row.eventType ?? row.event_type ?? '-';
}

function timelineOperator(row: AlertTimelineRow) {
  return row.operatorName ?? row.operator_name ?? '-';
}

function timelineAssignee(row: AlertTimelineRow) {
  return row.assignedTo ?? row.assigned_to ?? row.assignee;
}

function timelineCreatedAt(row: AlertTimelineRow) {
  return row.createdAt ?? row.created_at;
}

function deliveryStatusColor(status?: string) {
  if (status === 'success') {
    return 'success';
  }
  if (status === 'failed' || status === 'error') {
    return 'error';
  }
  return 'processing';
}

export default function AlertsPage() {
  const [rows, setRows] = useState<AlertRow[]>([]);
  const [statusFilter, setStatusFilter] = useState<AlertStatusFilter>('all');
  const [channels, setChannels] = useState<NotificationChannelRow[]>([]);
  const [deliveries, setDeliveries] = useState<NotificationDeliveryRow[]>([]);
  const [timeline, setTimeline] = useState<AlertTimelineRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [sending, setSending] = useState(false);
  const [lifecycleSubmitting, setLifecycleSubmitting] = useState(false);
  const [activeRow, setActiveRow] = useState<AlertRow | null>(null);
  const [notificationRow, setNotificationRow] = useState<AlertRow | null>(null);
  const [lifecycleRow, setLifecycleRow] = useState<AlertRow | null>(null);
  const [lifecycleAction, setLifecycleAction] = useState<AlertLifecycleAction | null>(null);
  const [sendResult, setSendResult] = useState<NotificationAlertSendResult | null>(null);
  const [form] = Form.useForm<{ decisionId: number }>();
  const [notificationForm] = Form.useForm<AlertNotificationFormValues>();
  const [lifecycleForm] = Form.useForm<AlertLifecycleFormValues>();

  async function load() {
    setLoading(true);
    try {
      const statusQuery = statusFilter === 'all' ? '' : `&status=${encodeURIComponent(statusFilter)}`;
      setRows(await apiGet<AlertRow[]>(`/api/core/alerts?limit=50${statusQuery}`));
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [statusFilter]);

  useEffect(() => {
    void loadChannels();
  }, []);

  async function loadChannels() {
    try {
      setChannels(await apiGet<NotificationChannelRow[]>('/api/notifications/channels'));
    } catch {
      setChannels([]);
    }
  }

  async function loadDeliveries(row: AlertRow) {
    try {
      setDeliveries(await apiGet<NotificationDeliveryRow[]>(`/api/notifications/deliveries?limit=50&alertId=${alertId(row)}`));
    } catch {
      setDeliveries([]);
    }
  }

  async function loadTimeline(row: AlertRow) {
    setTimelineLoading(true);
    try {
      setTimeline(await apiGet<AlertTimelineRow[]>(`/api/core/alerts/${alertId(row)}/timeline`));
    } catch {
      setTimeline([]);
    } finally {
      setTimelineLoading(false);
    }
  }

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

  function openDetailDrawer(row: AlertRow) {
    setActiveRow(row);
    setTimeline([]);
    void loadTimeline(row);
  }

  function openNotificationModal(row: AlertRow) {
    if (row.status !== 'open') {
      message.warning('只有 open alert 可以手动发送通知');
      return;
    }
    setNotificationRow(row);
    setSendResult(null);
    setDeliveries([]);
    notificationForm.resetFields();
    void loadChannels();
    void loadDeliveries(row);
  }

  function openLifecycleModal(action: AlertLifecycleAction, row: AlertRow) {
    setLifecycleAction(action);
    setLifecycleRow(row);
    lifecycleForm.setFieldsValue({
      operatorName: 'admin',
      assignee: action === 'assign' ? assignedTo(row) : undefined,
      note: undefined,
    });
  }

  async function submitLifecycle(values: AlertLifecycleFormValues) {
    if (!lifecycleRow || !lifecycleAction) {
      return;
    }
    setLifecycleSubmitting(true);
    try {
      const request: AlertLifecycleRequest = {
        operatorName: values.operatorName,
        assignee: values.assignee ?? '',
        note: values.note ?? '',
      };
      const updatedAlert = await apiPost<AlertRow | null>(`/api/core/alerts/${alertId(lifecycleRow)}/${lifecycleAction}`, request);
      message.success('告警生命周期操作已提交');
      setLifecycleRow(null);
      setLifecycleAction(null);
      lifecycleForm.resetFields();
      await load();
      if (activeRow && alertId(activeRow) === alertId(lifecycleRow)) {
        setActiveRow({ ...activeRow, ...lifecycleRow, ...(updatedAlert ?? {}) });
        await loadTimeline(lifecycleRow);
      }
    } finally {
      setLifecycleSubmitting(false);
    }
  }

  function renderLifecycleActions(row: AlertRow) {
    if (row.status === 'closed') {
      return (
        <Button size="small" icon={<EyeOutlined />} onClick={() => openDetailDrawer(row)}>
          详情/时间线
        </Button>
      );
    }

    if (row.status === 'acknowledged') {
      return (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => openDetailDrawer(row)}>
            详情
          </Button>
          <Button size="small" icon={<UserSwitchOutlined />} onClick={() => openLifecycleModal('assign', row)}>
            指派
          </Button>
          <Button size="small" icon={<CloseCircleOutlined />} onClick={() => openLifecycleModal('close', row)}>
            关闭
          </Button>
        </Space>
      );
    }

    if (row.status === 'open') {
      return (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => openDetailDrawer(row)}>
            详情
          </Button>
          <Button size="small" icon={<CheckOutlined />} onClick={() => openLifecycleModal('acknowledge', row)}>
            确认
          </Button>
          <Button size="small" icon={<UserSwitchOutlined />} onClick={() => openLifecycleModal('assign', row)}>
            指派
          </Button>
          <Button size="small" icon={<CloseCircleOutlined />} onClick={() => openLifecycleModal('close', row)}>
            关闭
          </Button>
          <Button size="small" icon={<SendOutlined />} onClick={() => openNotificationModal(row)}>
            发送通知
          </Button>
        </Space>
      );
    }

    return (
      <Space>
        <Button size="small" icon={<EyeOutlined />} onClick={() => openDetailDrawer(row)}>
          详情
        </Button>
      </Space>
    );
  }

  async function sendNotification(values: AlertNotificationFormValues) {
    if (!notificationRow) {
      return;
    }
    setSending(true);
    try {
      const request: NotificationAlertSendRequest = {
        alertId: alertId(notificationRow),
        channelId: values.channelId,
      };
      const result = await apiPost<NotificationAlertSendResult>('/api/notifications/alerts/send', request);
      setSendResult(result);
      if (result.status === 'success') {
        message.success(result.message || '通知发送成功');
      } else {
        message.warning(result.message || '通知发送失败');
      }
      await loadDeliveries(notificationRow);
    } finally {
      setSending(false);
    }
  }

  const enabledNotificationChannels = channels.filter((channel) => {
    const type = channelType(channel);
    return channel.enabled && (type === 'webhook' || type === 'wecom' || type === 'feishu');
  });

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
    { title: '指派给', width: 120, render: (_, row) => assignedTo(row) || '-' },
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
      title: '更新时间',
      width: 150,
      render: (_, row) => formatTime(updatedAt(row)),
    },
    {
      title: '操作',
      width: 340,
      fixed: 'right',
      render: (_, row) => renderLifecycleActions(row),
    },
  ];

  const lifecycleTitle = {
    acknowledge: '确认告警',
    assign: '指派告警',
    close: '关闭告警',
  }[lifecycleAction ?? 'acknowledge'];

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
          message="本阶段只处理 alert 生命周期：确认、指派、关闭与 open alert 手动发送通知；不自动发送通知，不做重试、升级、通知编排。"
        />
        <Space className="form-hint" wrap>
          <Typography.Text type="secondary">状态筛选</Typography.Text>
          <Select<AlertStatusFilter>
            value={statusFilter}
            style={{ width: 180 }}
            onChange={setStatusFilter}
            options={[
              { value: 'all', label: '全部' },
              { value: 'open', label: 'Open' },
              { value: 'acknowledged', label: 'Acknowledged' },
              { value: 'closed', label: 'Closed' },
            ]}
          />
        </Space>
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
          scroll={{ x: 1480 }}
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
              <span>Assigned To</span>
              <strong>{assignedTo(activeRow) || '-'}</strong>
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
              <span>Updated At</span>
              <strong>{formatTime(updatedAt(activeRow))}</strong>
            </div>
            <Typography.Paragraph className="json-preview">
              <pre>{detailText(activeRow.detail || activeRow.detail_json)}</pre>
            </Typography.Paragraph>
            <Typography.Title level={5}>生命周期时间线</Typography.Title>
            {timeline.length > 0 ? (
              <Timeline
                pending={timelineLoading ? '加载中...' : false}
                items={timeline.map((item) => ({
                  key: item.id,
                  children: (
                    <Space direction="vertical" size={2}>
                      <Space wrap>
                        <Tag>{timelineAction(item)}</Tag>
                        <Typography.Text>{timelineOperator(item)}</Typography.Text>
                        {timelineAssignee(item) ? (
                          <Typography.Text type="secondary">指派给 {timelineAssignee(item)}</Typography.Text>
                        ) : null}
                      </Space>
                      <Typography.Text type="secondary">{formatTime(timelineCreatedAt(item))}</Typography.Text>
                      {item.note ? <Typography.Text>{item.note}</Typography.Text> : null}
                    </Space>
                  ),
                }))}
              />
            ) : (
              <Empty description={timelineLoading ? '时间线加载中...' : '暂无生命周期事件'} />
            )}
          </Space>
        ) : (
          <Empty />
        )}
      </Drawer>
      <Modal
        title="发送通知"
        open={Boolean(notificationRow)}
        onCancel={() => setNotificationRow(null)}
        footer={null}
        destroyOnHidden
      >
        {notificationRow ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary">告警标题</Typography.Text>
              <Typography.Title level={5} style={{ marginTop: 4, marginBottom: 8 }}>
                {notificationRow.title}
              </Typography.Title>
              <Space wrap>
                <Tag color={severityColor(notificationRow.severity)}>{severityLabel(notificationRow.severity)}</Tag>
                <Tag color={statusColor(notificationRow.status)}>{statusLabel(notificationRow.status)}</Tag>
              </Space>
            </div>

            <Form form={notificationForm} layout="vertical" onFinish={sendNotification}>
              <Form.Item
                name="channelId"
                label="Notification Channel"
                rules={[{ required: true, message: '请选择一个已启用的通知通道' }]}
              >
                <Select
                  placeholder="选择一个已启用的通知通道"
                  options={enabledNotificationChannels.map((channel) => ({
                    value: channel.id,
                    label: `${channel.name} / ${notificationChannelTypeLabel(channelType(channel))}`,
                  }))}
                  notFoundContent="暂无已启用的通知通道"
                />
              </Form.Item>
              <Button
                type="primary"
                htmlType="submit"
                icon={<BellOutlined />}
                loading={sending}
                disabled={enabledNotificationChannels.length === 0}
              >
                发送通知
              </Button>
            </Form>

            {sendResult ? (
              <Alert
                type={sendResult.status === 'failed' || sendResult.failed ? 'warning' : 'success'}
                showIcon
                message="发送结果"
                description={
                  sendResult.message ||
                  `成功 ${sendResult.success ?? (sendResult.status === 'success' ? 1 : 0)}，失败 ${
                    sendResult.failed ?? (sendResult.status === 'failed' ? 1 : 0)
                  }`
                }
              />
            ) : null}

            <Table<NotificationDeliveryRow>
              size="small"
              rowKey="id"
              dataSource={deliveries}
              pagination={false}
              columns={[
                {
                  title: '通道',
                  dataIndex: 'channel_name',
                  render: (value) => value || '-',
                },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 90,
                  render: (value) => <Tag color={deliveryStatusColor(value)}>{value || '-'}</Tag>,
                },
                {
                  title: '发送时间',
                  dataIndex: 'created_at',
                  width: 150,
                  render: formatTime,
                },
              ]}
              locale={{ emptyText: '告警手动触发通知后会在这里显示' }}
            />
          </Space>
        ) : null}
      </Modal>
      <Modal
        title={lifecycleTitle}
        open={Boolean(lifecycleRow)}
        onCancel={() => {
          setLifecycleRow(null);
          setLifecycleAction(null);
        }}
        onOk={() => lifecycleForm.submit()}
        okText="提交"
        confirmLoading={lifecycleSubmitting}
        destroyOnHidden
      >
        {lifecycleRow ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary">告警标题</Typography.Text>
              <Typography.Title level={5} style={{ marginTop: 4, marginBottom: 8 }}>
                {lifecycleRow.title}
              </Typography.Title>
              <Space wrap>
                <Tag color={severityColor(lifecycleRow.severity)}>{severityLabel(lifecycleRow.severity)}</Tag>
                <Tag color={statusColor(lifecycleRow.status)}>{statusLabel(lifecycleRow.status)}</Tag>
              </Space>
            </div>
            <Form form={lifecycleForm} layout="vertical" onFinish={submitLifecycle} initialValues={{ operatorName: 'admin' }}>
              <Form.Item
                name="operatorName"
                label="Operator"
                rules={[{ required: true, message: '请输入 operatorName' }]}
              >
                <Input placeholder="admin" />
              </Form.Item>
              {lifecycleAction === 'assign' ? (
                <Form.Item
                  name="assignee"
                  label="Assignee"
                  rules={[{ required: true, whitespace: true, message: '请输入 assignee' }]}
                >
                  <Input placeholder="例如：admin 或 oncall-user" />
                </Form.Item>
              ) : null}
              <Form.Item
                name="note"
                label="Note"
                rules={
                  lifecycleAction === 'close'
                    ? [{ required: true, whitespace: true, message: '关闭告警必须填写 note' }]
                    : undefined
                }
              >
                <Input.TextArea
                  rows={4}
                  placeholder={lifecycleAction === 'acknowledge' ? '可选：确认说明' : '请输入操作说明'}
                />
              </Form.Item>
            </Form>
          </Space>
        ) : null}
      </Modal>
    </div>
  );
}
