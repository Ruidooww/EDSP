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

const CHANNEL_TYPE_OPTIONS = [
  { value: 'webhook', label: 'Webhook' },
];

function channelTypeLabel(value: string) {
  return {
    webhook: 'Webhook',
    wecom: '企业微信',
    feishu: '飞书',
    sms: '短信',
    email: '邮件',
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

export default function NotificationsPage() {
  const [rows, setRows] = useState<NotificationChannelRow[]>([]);
  const [deliveries, setDeliveries] = useState<NotificationDeliveryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [deliveryAlertId, setDeliveryAlertId] = useState<number | null>(null);
  const [activeDelivery, setActiveDelivery] = useState<NotificationDeliveryRow | null>(null);
  const [form] = Form.useForm<NotificationChannelFormValues>();

  function deliveriesPath(alertId = deliveryAlertId) {
    return `/api/notifications/deliveries?limit=50${alertId ? `&alertId=${alertId}` : ''}`;
  }

  async function load(alertId = deliveryAlertId) {
    setLoading(true);
    try {
      const [channelRows, deliveryRows] = await Promise.all([
        apiGet<NotificationChannelRow[]>('/api/notifications/channels'),
        apiGet<NotificationDeliveryRow[]>(deliveriesPath(alertId)),
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

  async function applyDeliveryAlertFilter(value: number | null) {
    setDeliveryAlertId(value);
    await load(value);
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

  function openCreate() {
    setOpen(true);
    form.setFieldsValue({
      channelType: 'webhook',
      enabled: true,
    });
  }

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
      title: '最近测试',
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
      title: '响应',
      dataIndex: 'response_body',
      ellipsis: true,
      render: (value?: string) => value || '-',
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
      width: 100,
      render: (_, row) => (
        <Button size="small" icon={<EyeOutlined />} onClick={() => setActiveDelivery(row)}>
          详情
        </Button>
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
        <InputNumber
          min={1}
          precision={0}
          placeholder="Alert ID"
          value={deliveryAlertId ?? undefined}
          onChange={(value) => setDeliveryAlertId(typeof value === 'number' ? value : null)}
        />
        <Button type="primary" onClick={() => applyDeliveryAlertFilter(deliveryAlertId)}>
          按告警查询
        </Button>
        <Button onClick={() => applyDeliveryAlertFilter(null)}>查看全部</Button>
      </Space>
      <Table<NotificationDeliveryRow>
        rowKey="id"
        loading={loading}
        dataSource={deliveries}
        columns={deliveryColumns}
        scroll={{ x: 1080 }}
        locale={{ emptyText: '告警手动触发通知后会在这里显示' }}
      />

      <Modal title="新增通知通道" open={open} onOk={submit} onCancel={() => setOpen(false)} okText="保存" destroyOnHidden>
        <Form layout="vertical" form={form} initialValues={{ channelType: 'webhook', enabled: true }}>
          <Form.Item name="name" label="通道名称" rules={[{ required: true, message: '请输入通道名称' }]}>
            <Input prefix={<CheckCircleOutlined />} placeholder="例如：安全运营 Webhook、企业微信值班群、短信告警" />
          </Form.Item>

          <Form.Item name="channelType" label="通道类型">
            <Select options={CHANNEL_TYPE_OPTIONS} />
          </Form.Item>

          <Form.Item
            name="webhookUrl"
            label="通道地址"
            rules={[
              { required: true, message: '请输入通道地址' },
              { type: 'url', message: '请输入合法 URL，例如 https://example.com/webhook' },
            ]}
          >
            <Input prefix={<ApiOutlined />} placeholder="https://example.com/webhook" />
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
              <Descriptions.Item label="响应内容">{activeDelivery.response_body || '-'}</Descriptions.Item>
              <Descriptions.Item label="发送时间">{formatTime(activeDelivery.created_at)}</Descriptions.Item>
            </Descriptions>
            <Typography.Paragraph className="json-preview">
              <pre>{payloadText(activeDelivery.payload_json)}</pre>
            </Typography.Paragraph>
          </Space>
        )}
      </Drawer>
    </Card>
  );
}
