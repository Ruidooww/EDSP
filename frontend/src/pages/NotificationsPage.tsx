import {
  ApiOutlined,
  CheckCircleOutlined,
  EyeOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../api';
import type { NotificationChannelRow, NotificationDeliveryRow } from '../types';

interface NotificationChannelFormValues {
  name: string;
  channelType: string;
  webhookUrl: string;
  description?: string;
  enabled: boolean;
}

interface DeliveryFilters {
  alertId: number | null;
  status: string;
  channelType: string;
  channelId: number | null;
}

const CHANNEL_TYPE_OPTIONS = [
  { value: 'webhook', label: 'Webhook' },
  { value: 'wecom', label: '企业微信' },
  { value: 'feishu', label: '飞书' },
];

const DELIVERY_STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'success', label: '成功' },
  { value: 'failed', label: '失败' },
];

const DELIVERY_CHANNEL_TYPE_OPTIONS = [
  { value: '', label: '全部通道类型' },
  ...CHANNEL_TYPE_OPTIONS,
];

function channelTypeLabel(value: string) {
  return {
    webhook: 'Webhook',
    wecom: '企业微信',
    feishu: '飞书',
  }[value] ?? value;
}

function statusTag(status: string) {
  if (status === 'ready' || status === 'success') {
    return <Tag color="success">正常</Tag>;
  }
  if (status === 'error' || status === 'failed') {
    return <Tag color="error">异常</Tag>;
  }
  if (status === 'disabled') {
    return <Tag>停用</Tag>;
  }
  return <Tag color="processing">待测试</Tag>;
}

function deliveryStatusTag(status: string) {
  if (status === 'success') {
    return <Tag color="success">成功</Tag>;
  }
  if (status === 'failed') {
    return <Tag color="error">失败</Tag>;
  }
  return <Tag color="processing">{status}</Tag>;
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

function payloadText(value?: NotificationDeliveryRow['payload_json']) {
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

function responseText(value?: string) {
  return value?.trim() || '-';
}

function nullableText(value?: string | number | null) {
  return value === null || value === undefined || value === '' ? '-' : String(value);
}

function retryableTag(value?: boolean) {
  if (value === true) {
    return <Tag color="processing">可重试</Tag>;
  }
  if (value === false) {
    return <Tag>不重试</Tag>;
  }
  return '-';
}

export default function NotificationsPage() {
  const [rows, setRows] = useState<NotificationChannelRow[]>([]);
  const [deliveries, setDeliveries] = useState<NotificationDeliveryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [deliveryAlertId, setDeliveryAlertId] = useState<number | null>(null);
  const [deliveryStatus, setDeliveryStatus] = useState('');
  const [deliveryChannelType, setDeliveryChannelType] = useState('');
  const [deliveryChannelId, setDeliveryChannelId] = useState<number | null>(null);
  const [activeDelivery, setActiveDelivery] = useState<NotificationDeliveryRow | null>(null);
  const [retryLoadingId, setRetryLoadingId] = useState<number | null>(null);
  const [form] = Form.useForm<NotificationChannelFormValues>();

  function currentDeliveryFilters(): DeliveryFilters {
    return {
      alertId: deliveryAlertId,
      status: deliveryStatus,
      channelType: deliveryChannelType,
      channelId: deliveryChannelId,
    };
  }

  function deliveriesPath(filters = currentDeliveryFilters()) {
    const params = new URLSearchParams({ limit: '50' });
    if (filters.alertId) {
      params.set('alertId', String(filters.alertId));
    }
    if (filters.status) {
      params.set('status', filters.status);
    }
    if (filters.channelType) {
      params.set('channelType', filters.channelType);
    }
    if (filters.channelId) {
      params.set('channelId', String(filters.channelId));
    }
    return `/api/notifications/deliveries?${params.toString()}`;
  }

  async function load(filters = currentDeliveryFilters()) {
    setLoading(true);
    try {
      const [channelRows, deliveryRows] = await Promise.all([
        apiGet<NotificationChannelRow[]>('/api/notifications/channels'),
        apiGet<NotificationDeliveryRow[]>(deliveriesPath(filters)),
      ]);
      setRows(channelRows);
      setDeliveries(deliveryRows);
    } catch {
      setRows([]);
      setDeliveries([]);
    } finally {
      setLoading(false);
    }
  }

  async function applyDeliveryFilters() {
    await load();
  }

  async function resetDeliveryFilters() {
    const filters: DeliveryFilters = {
      alertId: null,
      status: '',
      channelType: '',
      channelId: null,
    };
    setDeliveryAlertId(filters.alertId);
    setDeliveryStatus(filters.status);
    setDeliveryChannelType(filters.channelType);
    setDeliveryChannelId(filters.channelId);
    await load(filters);
  }

  useEffect(() => {
    void load();
  }, []);

  async function submit() {
    const values = await form.validateFields();
    await apiPost('/api/notifications/channels', values);
    message.success('通知通道已保存');
    setOpen(false);
    form.resetFields();
    await load();
  }

  async function retryDelivery(row: NotificationDeliveryRow) {
    setRetryLoadingId(row.id);
    try {
      await apiPost(`/api/notifications/deliveries/${row.id}/retry`, {});
      message.success(`投递记录 #${row.id} 已提交重试`);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      message.error(`投递记录 #${row.id} 重试失败：${errorMessage}`);
    } finally {
      await load();
      setRetryLoadingId(null);
    }
  }

  function openCreate() {
    setOpen(true);
    form.setFieldsValue({
      channelType: 'webhook',
      enabled: true,
    });
  }

  const selectedChannelType = Form.useWatch('channelType', form) || 'webhook';
  const endpointLabel = selectedChannelType === 'wecom'
    ? '企业微信机器人 Webhook 地址'
    : selectedChannelType === 'feishu'
      ? '飞书机器人 Webhook 地址'
      : '通道地址';
  const endpointPlaceholder = selectedChannelType === 'wecom'
    ? 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...'
    : selectedChannelType === 'feishu'
      ? 'https://open.feishu.cn/open-apis/bot/v2/hook/...'
      : 'https://example.com/webhook';

  const channelColumns: ColumnsType<NotificationChannelRow> = [
    {
      title: '通道名称',
      dataIndex: 'name',
      render: (name: string, row) => (
        <Space>
          <span className="table-source-icon">
            <LinkOutlined />
          </span>
          <div>
            <strong>{name}</strong>
            <span className="table-subtext">{row.description || '未填写说明'}</span>
          </div>
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'channel_type',
      width: 110,
      render: (value: string) => <Tag color="blue">{channelTypeLabel(value)}</Tag>,
    },
    {
      title: '地址',
      dataIndex: 'endpoint_masked',
      ellipsis: true,
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 90,
      render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: statusTag,
    },
    {
      title: '最近状态',
      dataIndex: 'last_test_status',
      render: (_, row) => (
        <div>
          {statusTag(row.last_test_status || 'draft')}
          <span className="table-subtext">{row.last_test_message || formatTime(row.last_test_at)}</span>
        </div>
      ),
    },
  ];

  const deliveryColumns: ColumnsType<NotificationDeliveryRow> = [
    {
      title: '发送内容',
      dataIndex: 'title',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">
            {row.alert_title || '未关联告警'} {row.alert_id ? `/ #${row.alert_id}` : ''}
          </span>
        </div>
      ),
    },
    {
      title: '通道',
      dataIndex: 'channel_name',
      width: 180,
      render: (value: string, row) => (
        <div>
          <span>{value || '-'}</span>
          <span className="table-subtext">{channelTypeLabel(row.channel_type || 'webhook')}</span>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: deliveryStatusTag,
    },
    {
      title: 'HTTP',
      dataIndex: 'response_code',
      width: 90,
      render: (value?: number) => value ?? '-',
    },
    {
      title: '失败类型',
      dataIndex: 'failure_type',
      width: 130,
      ellipsis: true,
      render: (value?: string | null) => nullableText(value),
    },
    {
      title: '失败原因',
      dataIndex: 'failure_reason',
      width: 220,
      ellipsis: true,
      render: (value?: string | null) => (
        <Typography.Text ellipsis={{ tooltip: nullableText(value) }}>
          {nullableText(value)}
        </Typography.Text>
      ),
    },
    {
      title: '是否可重试',
      dataIndex: 'retryable',
      width: 120,
      render: retryableTag,
    },
    {
      title: '重试次数',
      dataIndex: 'retry_count',
      width: 100,
      render: (value?: number) => nullableText(value),
    },
    {
      title: 'Retry Of',
      dataIndex: 'retry_of_delivery_id',
      width: 110,
      render: (value?: number | null) => nullableText(value),
    },
    {
      title: '响应',
      dataIndex: 'response_body',
      ellipsis: true,
      render: (value?: string) => (
        <Typography.Text code ellipsis>
          {responseText(value)}
        </Typography.Text>
      ),
    },
    {
      title: '发送时间',
      dataIndex: 'created_at',
      width: 150,
      render: formatTime,
    },
    {
      title: '操作',
      align: 'right',
      width: 180,
      render: (_, row) => (
        <Space>
          {row.status === 'failed' && row.retryable === true ? (
            <Button size="small" icon={<ReloadOutlined />} loading={retryLoadingId === row.id} onClick={() => retryDelivery(row)}>
              重试一次
            </Button>
          ) : null}
          <Button size="small" icon={<EyeOutlined />} onClick={() => setActiveDelivery(row)}>
            详情
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Card
      className="dashboard-card"
      title="通知中心"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => load()} loading={loading}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增通道
          </Button>
        </Space>
      }
    >
      <Alert
        className="form-hint"
        type="info"
        showIcon
        message="告警手动触发通知后会在这里显示"
      />

      <Typography.Title level={5} className="section-subtitle">
        通知通道
      </Typography.Title>
      <Table<NotificationChannelRow>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={channelColumns}
        scroll={{ x: 980 }}
        locale={{ emptyText: '暂无通知通道，可以先新增一个推送通道。' }}
      />

      <Typography.Title level={5} className="section-subtitle">
        发送记录
      </Typography.Title>
      <Space className="form-hint" wrap>
        <Select
          style={{ width: 140 }}
          value={deliveryStatus}
          options={DELIVERY_STATUS_OPTIONS}
          onChange={setDeliveryStatus}
        />
        <Select
          style={{ width: 160 }}
          value={deliveryChannelType}
          options={DELIVERY_CHANNEL_TYPE_OPTIONS}
          onChange={setDeliveryChannelType}
        />
        <InputNumber
          min={1}
          precision={0}
          placeholder="Channel ID"
          value={deliveryChannelId ?? undefined}
          onChange={(value) => setDeliveryChannelId(typeof value === 'number' ? value : null)}
        />
        <InputNumber
          min={1}
          precision={0}
          placeholder="Alert ID"
          value={deliveryAlertId ?? undefined}
          onChange={(value) => setDeliveryAlertId(typeof value === 'number' ? value : null)}
        />
        <Button type="primary" onClick={applyDeliveryFilters}>
          查询
        </Button>
        <Button onClick={resetDeliveryFilters}>查看全部</Button>
      </Space>
      <Table<NotificationDeliveryRow>
        rowKey="id"
        loading={loading}
        dataSource={deliveries}
        columns={deliveryColumns}
        scroll={{ x: 1760 }}
        locale={{ emptyText: '告警手动触发通知后会在这里显示' }}
      />

      <Modal title="新增通知通道" open={open} onOk={submit} onCancel={() => setOpen(false)} okText="保存" destroyOnHidden>
        <Form layout="vertical" form={form} initialValues={{ channelType: 'webhook', enabled: true }}>
          <Form.Item name="name" label="通道名称" rules={[{ required: true, message: '请输入通道名称' }]}>
            <Input prefix={<CheckCircleOutlined />} placeholder="例如：安全运营 Webhook、企业微信、飞书" />
          </Form.Item>

          <Form.Item name="channelType" label="通道类型">
            <Select options={CHANNEL_TYPE_OPTIONS} />
          </Form.Item>

          <Form.Item
            name="webhookUrl"
            label={endpointLabel}
            rules={[
              { required: true, message: `请输入${endpointLabel}` },
              { type: 'url', message: '请输入合法 URL，例如 https://example.com/webhook' },
            ]}
          >
            <Input prefix={<ApiOutlined />} placeholder={endpointPlaceholder} />
          </Form.Item>

          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="记录通道用途、负责人、发送范围等信息" />
          </Form.Item>

          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer title="通知发送详情" width={640} open={Boolean(activeDelivery)} onClose={() => setActiveDelivery(null)} destroyOnHidden>
        {activeDelivery && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="标题">{activeDelivery.title}</Descriptions.Item>
              <Descriptions.Item label="状态">{deliveryStatusTag(activeDelivery.status)}</Descriptions.Item>
              <Descriptions.Item label="通道">
                {activeDelivery.channel_name || '-'} / {channelTypeLabel(activeDelivery.channel_type || 'webhook')}
              </Descriptions.Item>
              <Descriptions.Item label="关联告警">
                {activeDelivery.alert_title || '-'} {activeDelivery.alert_id ? `/#${activeDelivery.alert_id}` : ''}
              </Descriptions.Item>
              <Descriptions.Item label="HTTP 状态">{activeDelivery.response_code ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="失败类型">{nullableText(activeDelivery.failure_type)}</Descriptions.Item>
              <Descriptions.Item label="失败原因">{nullableText(activeDelivery.failure_reason)}</Descriptions.Item>
              <Descriptions.Item label="是否可重试">{retryableTag(activeDelivery.retryable)}</Descriptions.Item>
              <Descriptions.Item label="重试次数">{nullableText(activeDelivery.retry_count)}</Descriptions.Item>
              <Descriptions.Item label="Retry Of">{nullableText(activeDelivery.retry_of_delivery_id)}</Descriptions.Item>
              <Descriptions.Item label="发送时间">{formatTime(activeDelivery.created_at)}</Descriptions.Item>
            </Descriptions>
            <div>
              <Typography.Text strong>已脱敏响应预览</Typography.Text>
              <Typography.Paragraph className="json-preview">
                <pre>{responseText(activeDelivery.response_body)}</pre>
              </Typography.Paragraph>
            </div>
            <div>
              <Typography.Text strong>已脱敏载荷预览</Typography.Text>
              <Typography.Paragraph className="json-preview">
                <pre>{payloadText(activeDelivery.payload_json)}</pre>
              </Typography.Paragraph>
            </div>
          </Space>
        )}
      </Drawer>
    </Card>
  );
}
