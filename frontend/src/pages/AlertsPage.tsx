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
  Collapse,
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
import AdvancedDetailsCollapse from '../components/AdvancedDetailsCollapse';
import BusinessStatusTag from '../components/BusinessStatusTag';
import NextStepHint from '../components/NextStepHint';
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
import {
  formatBusinessTime,
  getAlertStatus,
  getDeliveryStatus,
  getEventTypeLabel,
  getSeverityColor,
  getSeverityLabel,
} from '../utils/businessDisplay';

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
  return getSeverityLabel(value);
}

function severityColor(value?: string) {
  return getSeverityColor(value);
}

function statusLabel(value?: string) {
  return getAlertStatus(value).label;
}

function statusColor(value?: string) {
  return getAlertStatus(value).color;
}

function formatTime(value?: string | number) {
  return formatBusinessTime(value);
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
  const value = row.action ?? row.eventType ?? row.event_type;
  return {
    acknowledge: '确认告警',
    acknowledged: '确认告警',
    assign: '指派处理',
    assigned: '指派处理',
    close: '关闭告警',
    closed: '关闭告警',
  }[value ?? ''] ?? '生命周期更新';
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
  return getDeliveryStatus(status).color;
}

function alertSourceLabel(row: AlertRow) {
  const source = row.sourceSystem || row.source_system;
  return source || '来源待确认';
}

function alertTypeLabel(row: AlertRow) {
  return getEventTypeLabel(row.alertType || row.alert_type);
}

function compactText(value?: string | number | null, width = 140) {
  const text = value === null || value === undefined || value === '' ? '-' : String(value);
  return (
    <Typography.Text className="alert-table-ellipsis" style={{ maxWidth: width }} ellipsis={{ tooltip: text }}>
      {text}
    </Typography.Text>
  );
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
      message.success(`告警${action}，可在列表中查看处置进展。`);
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
      message.warning('只有开放状态的告警可以手动发送通知');
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
        <Space className="alert-actions" wrap={false}>
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
        <Space className="alert-actions" wrap={false}>
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
      <Space className="alert-actions" wrap={false}>
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
      width: 320,
      ellipsis: true,
      render: (title: string, row) => (
        <div className="alert-title-cell">
          <strong>{title}</strong>
          <span className="table-subtext alert-subtitle-cell">
            {alertSourceLabel(row)} / {alertTypeLabel(row)}
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
      width: 100,
      render: (value: string) => <Tag color={statusColor(value)}>{statusLabel(value)}</Tag>,
    },
    { title: '规则', width: 220, ellipsis: true, render: (_, row) => compactText(ruleName(row), 200) },
    { title: '指派给', width: 130, ellipsis: true, render: (_, row) => compactText(assignedTo(row), 110) },
    { title: '用户', dataIndex: 'actor', width: 150, ellipsis: true, render: (value) => compactText(value, 130) },
    {
      title: '资产',
      width: 220,
      ellipsis: true,
      render: (_, row) => compactText(row.assetRef || row.asset_ref, 200),
    },
    {
      title: '发生时间',
      width: 160,
      render: (_, row) => compactText(formatTime(row.occurredAt || row.occurred_at), 140),
    },
    {
      title: '更新时间',
      width: 160,
      render: (_, row) => compactText(formatTime(updatedAt(row)), 140),
    },
    {
      title: '操作',
      width: 360,
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
          message="告警处置支持确认、指派、关闭和手动通知。系统不会在本页面自动发送通知。"
        />
        <NextStepHint
          description="优先处理高危和开放状态告警。打开详情后查看来源、主体、资产、证据和处置建议，再选择确认、指派或关闭。"
        />
        <Space className="form-hint" wrap>
          <Typography.Text type="secondary">状态筛选</Typography.Text>
          <Select<AlertStatusFilter>
            value={statusFilter}
            style={{ width: 180 }}
            onChange={setStatusFilter}
            options={[
              { value: 'all', label: '全部' },
              { value: 'open', label: '开放' },
              { value: 'acknowledged', label: '已确认' },
              { value: 'closed', label: '已关闭' },
            ]}
          />
        </Space>
        <Collapse
          className="advanced-details-collapse form-hint"
          items={[
            {
              key: 'manual-alert-generation',
              label: '高级工具：从规则决策生成告警',
              children: (
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  <Typography.Text type="secondary">
                    用于管理员对已命中的规则决策补生成告警。系统正式同步链路会自动处理新命中的决策。
                  </Typography.Text>
                  <Form form={form} layout="inline" onFinish={generateAlert}>
                    <Form.Item
                      name="decisionId"
                      label="规则决策编号（高级）"
                      rules={[{ required: true, message: '请输入规则决策编号' }]}
                    >
                      <InputNumber min={1} precision={0} placeholder="例如：123" />
                    </Form.Item>
                    <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={generating}>
                      生成告警
                    </Button>
                  </Form>
                </Space>
              ),
            },
          ]}
        />
        <Table<AlertRow>
          className="alerts-table"
          rowKey={alertId}
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1900 }}
          expandable={{
            expandedRowRender: (row) => (
              <AdvancedDetailsCollapse
                title="告警技术详情"
                items={[
            { label: 'decisionId', value: decisionId(row), code: true },
            { label: 'standardEventId', value: standardEventId(row), code: true },
            { label: 'ruleId', value: ruleId(row), code: true },
                  { label: 'sourceSystem', value: row.sourceSystem || row.source_system, code: true },
                  { label: 'alertType', value: row.alertType || row.alert_type, code: true },
                  { label: 'externalId', value: row.externalId || row.external_id, code: true },
                ]}
              />
            ),
          }}
          locale={{ emptyText: '暂无匹配告警。当前筛选条件下没有告警，可切换状态范围，或等待规则决策链路生成告警。' }}
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
            <div className="business-summary-grid">
              {[
                ['来源系统', alertSourceLabel(activeRow)],
                ['事件类型', alertTypeLabel(activeRow)],
                ['涉及主体', activeRow.actor || activeRow.subjectRef || activeRow.subject_ref || '-'],
                ['涉及资产', activeRow.assetRef || activeRow.asset_ref || '-'],
                ['相关规则', ruleName(activeRow)],
                ['指派处理', assignedTo(activeRow) || '暂未指派'],
                ['发生时间', formatTime(activeRow.occurredAt || activeRow.occurred_at)],
                ['更新时间', formatTime(updatedAt(activeRow))],
              ].map(([label, value]) => (
                <div className="business-summary-item" key={label}>
                  <span>{label}</span>
                  <strong>{value}</strong>
                </div>
              ))}
            </div>
            <NextStepHint
              description="请先确认告警是否真实，再指派处理人跟进。若确认已处置，可关闭告警；如属于误报，在备注中说明原因。"
            />
            <AdvancedDetailsCollapse
              title="高级字段"
              items={[
                { label: 'decisionId', value: decisionId(activeRow), code: true },
                { label: 'standardEventId', value: standardEventId(activeRow), code: true },
                { label: 'ruleId', value: ruleId(activeRow), code: true },
                { label: 'sourceSystem', value: activeRow.sourceSystem || activeRow.source_system, code: true },
                { label: 'externalId', value: activeRow.externalId || activeRow.external_id, code: true },
                { label: 'alertType', value: activeRow.alertType || activeRow.alert_type, code: true },
                { label: 'detailJson', value: detailText(activeRow.detail || activeRow.detail_json), code: true },
              ]}
            />
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
                        <Typography.Text>{timelineOperator(item) === '-' ? '系统记录' : timelineOperator(item)}</Typography.Text>
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
                label="通知通道"
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
                  render: (value) => <Tag color={deliveryStatusColor(value)}>{getDeliveryStatus(value).label}</Tag>,
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
                hidden
          rules={[{ required: true, message: '请输入操作者' }]}
              >
                <Input placeholder="admin" />
              </Form.Item>
              {lifecycleAction === 'assign' ? (
                <Form.Item
                  name="assignee"
                  label="指派给"
                  rules={[{ required: true, whitespace: true, message: '请输入 assignee' }]}
                >
                  <Input placeholder="例如：admin 或 oncall-user" />
                </Form.Item>
              ) : null}
              <Form.Item
                name="note"
                label="处置说明"
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
