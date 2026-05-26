import {
  ApiOutlined,
  CheckCircleOutlined,
  EditOutlined,
  EyeOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useState } from 'react';
import { apiGet, apiPost, apiPut } from '../api';
import type {
  NotificationChannelRow,
  NotificationDeliveryRow,
  NotificationSecretBackfillDryRunItem,
  NotificationSecretBackfillDryRunResult,
  NotificationSecretBackfillRun,
  NotificationSecretBackfillRunItem,
  NotificationSecretBackfillRunListResult,
} from '../types';

interface NotificationChannelFormValues {
  name: string;
  channelType: string;
  webhookUrl?: string;
  description?: string;
  enabled: boolean;
}

interface DeliveryFilters {
  alertId: number | null;
  status: string;
  channelType: string;
  channelId: number | null;
}

interface ChannelFilters {
  secretStorageStatus: string;
  enabled: string;
}

interface DryRunFilters {
  enabled: string;
  channelType: string;
  limit: number | null;
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

const CHANNEL_SECRET_STORAGE_STATUS_OPTIONS = [
  { value: '', label: '全部密钥状态' },
  { value: 'encrypted', label: '已加密' },
  { value: 'legacy_plaintext', label: '待重配' },
  { value: 'missing', label: '未配置' },
];

const CHANNEL_ENABLED_OPTIONS = [
  { value: '', label: '全部启用状态' },
  { value: 'true', label: '启用' },
  { value: 'false', label: '停用' },
];

const BACKFILL_CONFIRMATION = 'EXECUTE_NOTIFICATION_SECRET_BACKFILL';

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

function secretStorageStatusTag(status?: string) {
  if (status === 'encrypted') {
    return <Tag color="success">已加密</Tag>;
  }
  if (status === 'legacy_plaintext') {
    return <Tag color="warning">待重配</Tag>;
  }
  if (status === 'missing') {
    return <Tag>未配置</Tag>;
  }
  return <Tag>{status || '-'}</Tag>;
}

function dryRunStatusTag(status?: string) {
  if (status === 'migration_eligible') {
    return <Tag color="success">理论可迁移</Tag>;
  }
  if (status === 'blocked') {
    return <Tag color="error">阻塞</Tag>;
  }
  if (status === 'already_encrypted') {
    return <Tag color="blue">已加密</Tag>;
  }
  if (status === 'missing') {
    return <Tag>未配置</Tag>;
  }
  return <Tag>{status || '-'}</Tag>;
}

function blockReasonLabel(reason?: string | null) {
  return {
    endpoint_missing: 'endpoint 缺失',
    endpoint_invalid: 'endpoint 无效',
    unsupported_channel_type: '不支持的通道类型',
  }[reason || ''] ?? '-';
}

function backfillRunStatusTag(status?: string) {
  if (status === 'completed') {
    return <Tag color="success">completed</Tag>;
  }
  if (status === 'completed_with_failures') {
    return <Tag color="warning">completed_with_failures</Tag>;
  }
  if (status === 'failed') {
    return <Tag color="error">failed</Tag>;
  }
  if (status === 'running') {
    return <Tag color="processing">running</Tag>;
  }
  return <Tag>{status || '-'}</Tag>;
}

function backfillItemStatusTag(status?: string) {
  if (status === 'migrated') {
    return <Tag color="success">migrated</Tag>;
  }
  if (status === 'skipped') {
    return <Tag>skipped</Tag>;
  }
  if (status === 'failed') {
    return <Tag color="error">failed</Tag>;
  }
  return <Tag>{status || '-'}</Tag>;
}

function backfillFailureReasonLabel(reason?: string | null) {
  return {
    not_found: 'not_found',
    already_encrypted: 'already_encrypted',
    not_legacy_plaintext: 'not_legacy_plaintext',
    endpoint_missing: 'endpoint_missing',
    endpoint_invalid: 'endpoint_invalid',
    unsupported_channel_type: 'unsupported_channel_type',
    notification_secret_key_missing: 'notification_secret_key_missing',
    notification_secret_key_invalid: 'notification_secret_key_invalid',
    notification_secret_store_failed: 'notification_secret_store_failed',
    unexpected_error: 'unexpected_error',
  }[reason || ''] ?? '-';
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
  const [dryRun, setDryRun] = useState<NotificationSecretBackfillDryRunResult | null>(null);
  const [backfillRuns, setBackfillRuns] = useState<NotificationSecretBackfillRun[]>([]);
  const [lastBackfillRun, setLastBackfillRun] = useState<NotificationSecretBackfillRun | null>(null);
  const [loading, setLoading] = useState(false);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [backfillExecuteLoading, setBackfillExecuteLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [backfillConfirmOpen, setBackfillConfirmOpen] = useState(false);
  const [editingRow, setEditingRow] = useState<NotificationChannelRow | null>(null);
  const [selectedBackfillChannelIds, setSelectedBackfillChannelIds] = useState<number[]>([]);
  const [backfillConfirmationInput, setBackfillConfirmationInput] = useState('');
  const [channelSecretStorageStatus, setChannelSecretStorageStatus] = useState('');
  const [channelEnabled, setChannelEnabled] = useState('');
  const [dryRunEnabled, setDryRunEnabled] = useState('');
  const [dryRunChannelType, setDryRunChannelType] = useState('');
  const [dryRunLimit, setDryRunLimit] = useState<number | null>(100);
  const [deliveryAlertId, setDeliveryAlertId] = useState<number | null>(null);
  const [deliveryStatus, setDeliveryStatus] = useState('');
  const [deliveryChannelType, setDeliveryChannelType] = useState('');
  const [deliveryChannelId, setDeliveryChannelId] = useState<number | null>(null);
  const [activeDelivery, setActiveDelivery] = useState<NotificationDeliveryRow | null>(null);
  const [retryLoadingId, setRetryLoadingId] = useState<number | null>(null);
  const [form] = Form.useForm<NotificationChannelFormValues>();

  function currentChannelFilters(): ChannelFilters {
    return {
      secretStorageStatus: channelSecretStorageStatus,
      enabled: channelEnabled,
    };
  }

  function currentDeliveryFilters(): DeliveryFilters {
    return {
      alertId: deliveryAlertId,
      status: deliveryStatus,
      channelType: deliveryChannelType,
      channelId: deliveryChannelId,
    };
  }

  function currentDryRunFilters(): DryRunFilters {
    return {
      enabled: dryRunEnabled,
      channelType: dryRunChannelType,
      limit: dryRunLimit,
    };
  }

  function channelsPath(filters = currentChannelFilters()) {
    const params = new URLSearchParams();
    if (filters.secretStorageStatus) {
      params.set('secretStorageStatus', filters.secretStorageStatus);
    }
    if (filters.enabled) {
      params.set('enabled', filters.enabled);
    }
    const query = params.toString();
    return query ? `/api/notifications/channels?${query}` : '/api/notifications/channels';
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

  function dryRunPath(filters = currentDryRunFilters()) {
    const params = new URLSearchParams();
    if (filters.enabled) {
      params.set('enabled', filters.enabled);
    }
    if (filters.channelType) {
      params.set('channelType', filters.channelType);
    }
    if (filters.limit) {
      params.set('limit', String(filters.limit));
    }
    const query = params.toString();
    return query ? `/api/notifications/secret-backfill/dry-run?${query}` : '/api/notifications/secret-backfill/dry-run';
  }

  async function load(channelFilters = currentChannelFilters(), deliveryFilters = currentDeliveryFilters()) {
    setLoading(true);
    try {
      const [channelRows, deliveryRows] = await Promise.all([
        apiGet<NotificationChannelRow[]>(channelsPath(channelFilters)),
        apiGet<NotificationDeliveryRow[]>(deliveriesPath(deliveryFilters)),
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

  async function loadDryRun(filters = currentDryRunFilters()) {
    setDryRunLoading(true);
    try {
      setDryRun(await apiGet<NotificationSecretBackfillDryRunResult>(dryRunPath(filters)));
      setSelectedBackfillChannelIds([]);
    } catch {
      setDryRun(null);
      setSelectedBackfillChannelIds([]);
    } finally {
      setDryRunLoading(false);
    }
  }

  async function loadBackfillRuns() {
    try {
      const result = await apiGet<NotificationSecretBackfillRunListResult>('/api/notifications/secret-backfill/runs?limit=5');
      setBackfillRuns(result.items);
    } catch {
      setBackfillRuns([]);
    }
  }

  async function applyChannelFilters() {
    await load();
  }

  async function updateChannelSecretStorageStatus(value: string) {
    const filters: ChannelFilters = {
      secretStorageStatus: value,
      enabled: channelEnabled,
    };
    setChannelSecretStorageStatus(value);
    await load(filters, currentDeliveryFilters());
  }

  async function updateChannelEnabled(value: string) {
    const filters: ChannelFilters = {
      secretStorageStatus: channelSecretStorageStatus,
      enabled: value,
    };
    setChannelEnabled(value);
    await load(filters, currentDeliveryFilters());
  }

  async function resetChannelFilters() {
    const filters: ChannelFilters = {
      secretStorageStatus: '',
      enabled: '',
    };
    setChannelSecretStorageStatus(filters.secretStorageStatus);
    setChannelEnabled(filters.enabled);
    await load(filters, currentDeliveryFilters());
  }

  async function updateDryRunEnabled(value: string) {
    const filters: DryRunFilters = {
      enabled: value,
      channelType: dryRunChannelType,
      limit: dryRunLimit,
    };
    setDryRunEnabled(value);
    await loadDryRun(filters);
  }

  async function updateDryRunChannelType(value: string) {
    const filters: DryRunFilters = {
      enabled: dryRunEnabled,
      channelType: value,
      limit: dryRunLimit,
    };
    setDryRunChannelType(value);
    await loadDryRun(filters);
  }

  async function applyDryRunFilters() {
    await loadDryRun();
  }

  async function resetDryRunFilters() {
    const filters: DryRunFilters = {
      enabled: '',
      channelType: '',
      limit: 100,
    };
    setDryRunEnabled(filters.enabled);
    setDryRunChannelType(filters.channelType);
    setDryRunLimit(filters.limit);
    await loadDryRun(filters);
  }

  async function showLegacyChannels() {
    const filters: ChannelFilters = {
      secretStorageStatus: 'legacy_plaintext',
      enabled: '',
    };
    setChannelSecretStorageStatus(filters.secretStorageStatus);
    setChannelEnabled(filters.enabled);
    await load(filters, currentDeliveryFilters());
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
    await load(currentChannelFilters(), filters);
  }

  useEffect(() => {
    void load();
    void loadDryRun();
    void loadBackfillRuns();
  }, []);

  async function submit() {
    const values = await form.validateFields();
    const endpoint = values.webhookUrl?.trim();
    if (editingRow && values.webhookUrl && !endpoint) {
      form.setFields([{ name: 'webhookUrl', errors: ['请输入有效通道地址，或留空保留现有密钥'] }]);
      return;
    }
    if (editingRow) {
      const payload: Record<string, unknown> = {
        name: values.name,
        channelType: editingRow.channel_type,
        description: values.description ?? null,
        enabled: values.enabled,
      };
      if (endpoint) {
        payload.webhookUrl = endpoint;
      }
      await apiPut(`/api/notifications/channels/${editingRow.id}`, payload);
      message.success('通知通道已更新');
    } else {
      await apiPost('/api/notifications/channels', {
        ...values,
        webhookUrl: endpoint,
      });
      message.success('通知通道已保存');
    }
    setOpen(false);
    setEditingRow(null);
    form.resetFields();
    await load();
    await loadDryRun();
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

  async function executeSelectedBackfill() {
    if (selectedBackfillChannelIds.length === 0 || selectedBackfillChannelIds.length > 50) {
      return;
    }
    if (backfillConfirmationInput !== BACKFILL_CONFIRMATION) {
      message.error('confirmation phrase mismatch');
      return;
    }
    setBackfillExecuteLoading(true);
    try {
      const run = await apiPost<NotificationSecretBackfillRun>('/api/notifications/secret-backfill/execute', {
        channelIds: selectedBackfillChannelIds,
        confirmation: BACKFILL_CONFIRMATION,
        requestedBy: 'manual',
      });
      const detail = await apiGet<NotificationSecretBackfillRun>(`/api/notifications/secret-backfill/runs/${run.id}`);
      setLastBackfillRun(detail);
      setBackfillConfirmOpen(false);
      setBackfillConfirmationInput('');
      setSelectedBackfillChannelIds([]);
      message.success(`backfill run #${run.id} completed`);
      await Promise.all([load(), loadDryRun(), loadBackfillRuns()]);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'unknown error';
      message.error(`backfill execution failed: ${errorMessage}`);
    } finally {
      setBackfillExecuteLoading(false);
    }
  }

  function openCreate() {
    setEditingRow(null);
    form.resetFields();
    setOpen(true);
    form.setFieldsValue({
      channelType: 'webhook',
      enabled: true,
    });
  }

  function openEdit(row: NotificationChannelRow) {
    setEditingRow(row);
    form.resetFields();
    setOpen(true);
    form.setFieldsValue({
      name: row.name,
      channelType: row.channel_type,
      webhookUrl: undefined,
      description: row.description,
      enabled: row.enabled,
    });
  }

  function closeModal() {
    setOpen(false);
    setEditingRow(null);
    form.resetFields();
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
  const endpointExtra = editingRow?.secret_storage_status === 'legacy_plaintext'
    ? '留空则保留现有密钥；重新输入 Webhook URL 后将转换为加密存储'
    : editingRow?.secret_storage_status === 'missing'
      ? '需要输入 endpoint 才能启用'
      : editingRow
        ? '留空则保留现有密钥'
        : undefined;

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
      title: '密钥状态',
      dataIndex: 'secret_storage_status',
      width: 110,
      render: secretStorageStatusTag,
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
    {
      title: '操作',
      align: 'right',
      width: 100,
      render: (_, row) => (
        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>
          编辑
        </Button>
      ),
    },
  ];

  const dryRunColumns: ColumnsType<NotificationSecretBackfillDryRunItem> = [
    {
      title: '通道名称',
      dataIndex: 'name',
      width: 180,
      ellipsis: true,
      render: (value: string) => (
        <Typography.Text ellipsis={{ tooltip: value }}>
          {value || '-'}
        </Typography.Text>
      ),
    },
    {
      title: '类型',
      dataIndex: 'channelType',
      width: 100,
      render: (value: string) => <Tag color="blue">{channelTypeLabel(value)}</Tag>,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 90,
      render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag>,
    },
    {
      title: '密钥状态',
      dataIndex: 'secretStorageStatus',
      width: 110,
      render: secretStorageStatusTag,
    },
    {
      title: '地址',
      dataIndex: 'endpointMasked',
      width: 220,
      ellipsis: true,
      render: (value: string) => <Typography.Text code ellipsis={{ tooltip: value }}>{value || '-'}</Typography.Text>,
    },
    {
      title: 'Dry Run',
      dataIndex: 'dryRunStatus',
      width: 130,
      render: dryRunStatusTag,
    },
    {
      title: '阻塞原因',
      dataIndex: 'blockReason',
      width: 150,
      render: blockReasonLabel,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 150,
      render: formatTime,
    },
  ];

  const backfillRunItemColumns: ColumnsType<NotificationSecretBackfillRunItem> = [
    {
      title: 'Channel ID',
      dataIndex: 'channel_id',
      width: 100,
    },
    {
      title: 'Type',
      dataIndex: 'channel_type',
      width: 110,
      render: (value?: string | null) => value ? <Tag color="blue">{channelTypeLabel(value)}</Tag> : '-',
    },
    {
      title: 'Item Status',
      dataIndex: 'item_status',
      width: 120,
      render: backfillItemStatusTag,
    },
    {
      title: 'Reason',
      dataIndex: 'failure_reason',
      width: 190,
      render: backfillFailureReasonLabel,
    },
    {
      title: 'Before',
      dataIndex: 'before_secret_storage_status',
      width: 150,
      render: secretStorageStatusTag,
    },
    {
      title: 'After',
      dataIndex: 'after_secret_storage_status',
      width: 150,
      render: secretStorageStatusTag,
    },
    {
      title: 'Endpoint',
      dataIndex: 'endpoint_masked',
      width: 220,
      ellipsis: true,
      render: (value?: string | null) => <Typography.Text code ellipsis={{ tooltip: value || '-' }}>{value || '-'}</Typography.Text>,
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

      <Card
        size="small"
        className="form-hint"
        title="密钥回填 Dry Run"
        extra={
          <Space>
            <Button
              type="primary"
              disabled={selectedBackfillChannelIds.length === 0 || selectedBackfillChannelIds.length > 50}
              onClick={() => setBackfillConfirmOpen(true)}
            >
              执行选中迁移
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => loadDryRun()} loading={dryRunLoading}>
              刷新 dry-run
            </Button>
            <Button onClick={showLegacyChannels}>查看 legacy 通道</Button>
          </Space>
        }
      >
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="这里只展示 legacy plaintext 通道的理论迁移资格，不执行 backfill，不清理历史数据。"
        />
        <Space className="form-hint" wrap>
          <Select
            style={{ width: 140 }}
            value={dryRunEnabled}
            options={CHANNEL_ENABLED_OPTIONS}
            onChange={(value) => void updateDryRunEnabled(value)}
          />
          <Select
            style={{ width: 160 }}
            value={dryRunChannelType}
            options={DELIVERY_CHANNEL_TYPE_OPTIONS}
            onChange={(value) => void updateDryRunChannelType(value)}
          />
          <InputNumber
            min={1}
            max={500}
            precision={0}
            placeholder="limit"
            value={dryRunLimit ?? undefined}
            onChange={(value) => setDryRunLimit(typeof value === 'number' ? value : null)}
          />
          <Button type="primary" onClick={applyDryRunFilters} loading={dryRunLoading}>
            查询
          </Button>
          <Button onClick={resetDryRunFilters}>查看全部</Button>
        </Space>
        <Space className="form-hint" wrap>
          <Tag>通道总数 {dryRun?.summary.totalChannels ?? 0}</Tag>
          <Tag color="warning">legacy {dryRun?.summary.legacyPlaintext ?? 0}</Tag>
          <Tag color="success">理论可迁移 {dryRun?.summary.migrationEligible ?? 0}</Tag>
          <Tag color="error">阻塞 {dryRun?.summary.blocked ?? 0}</Tag>
          <Tag color="blue">已加密 {dryRun?.summary.encrypted ?? 0}</Tag>
          <Tag>missing {dryRun?.summary.missing ?? 0}</Tag>
          <Tag>endpoint 缺失 {dryRun?.blockReasons.endpoint_missing ?? 0}</Tag>
          <Tag>endpoint 无效 {dryRun?.blockReasons.endpoint_invalid ?? 0}</Tag>
          <Tag>类型不支持 {dryRun?.blockReasons.unsupported_channel_type ?? 0}</Tag>
          {dryRun?.truncated ? <Tag color="warning">明细已按 limit 截断</Tag> : <Tag>明细未截断</Tag>}
          {selectedBackfillChannelIds.length > 50 ? <Tag color="error">单次最多 50 个通道</Tag> : null}
        </Space>
        <Table<NotificationSecretBackfillDryRunItem>
          rowKey="id"
          size="small"
          loading={dryRunLoading}
          dataSource={dryRun?.items ?? []}
          columns={dryRunColumns}
          rowSelection={{
            selectedRowKeys: selectedBackfillChannelIds,
            onChange: (keys) => setSelectedBackfillChannelIds(keys.map((key) => Number(key))),
            getCheckboxProps: (record) => ({
              disabled: !(record.migrationEligible === true && record.dryRunStatus === 'migration_eligible'),
            }),
          }}
          scroll={{ x: 1230 }}
          pagination={{ pageSize: 5 }}
          locale={{ emptyText: '暂无 dry-run 明细' }}
        />
        {lastBackfillRun ? (
          <Card size="small" className="form-hint" title={`最近执行 run #${lastBackfillRun.id}`}>
            <Space wrap className="form-hint">
              {backfillRunStatusTag(lastBackfillRun.status)}
              <Tag>requested {lastBackfillRun.total_requested}</Tag>
              <Tag color="success">eligible {lastBackfillRun.eligible_count}</Tag>
              <Tag color="success">migrated {lastBackfillRun.migrated_count}</Tag>
              <Tag>skipped {lastBackfillRun.skipped_count}</Tag>
              <Tag color={lastBackfillRun.failed_count ? 'error' : 'default'}>failed {lastBackfillRun.failed_count}</Tag>
            </Space>
            <Table<NotificationSecretBackfillRunItem>
              rowKey="id"
              size="small"
              dataSource={lastBackfillRun.items ?? []}
              columns={backfillRunItemColumns}
              pagination={{ pageSize: 5 }}
              scroll={{ x: 1040 }}
            />
          </Card>
        ) : null}
        {backfillRuns.length > 0 ? (
          <Space className="form-hint" wrap>
            <Typography.Text type="secondary">最近 runs</Typography.Text>
            {backfillRuns.map((run) => (
              <Tag key={run.id}>
                #{run.id} {run.status} / migrated {run.migrated_count}
              </Tag>
            ))}
          </Space>
        ) : null}
      </Card>

      <Typography.Title level={5} className="section-subtitle">
        通知通道
      </Typography.Title>
      <Space className="form-hint" wrap>
        <Select
          style={{ width: 160 }}
          value={channelSecretStorageStatus}
          options={CHANNEL_SECRET_STORAGE_STATUS_OPTIONS}
          onChange={(value) => void updateChannelSecretStorageStatus(value)}
        />
        <Select
          style={{ width: 140 }}
          value={channelEnabled}
          options={CHANNEL_ENABLED_OPTIONS}
          onChange={(value) => void updateChannelEnabled(value)}
        />
        <Button type="primary" onClick={applyChannelFilters}>
          查询
        </Button>
        <Button onClick={resetChannelFilters}>查看全部</Button>
      </Space>
      <Table<NotificationChannelRow>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={channelColumns}
        scroll={{ x: 1120 }}
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

      <Modal
        title={editingRow ? '编辑通知通道' : '新增通知通道'}
        open={open}
        onOk={submit}
        onCancel={closeModal}
        okText="保存"
        destroyOnHidden
      >
        <Form layout="vertical" form={form} initialValues={{ channelType: 'webhook', enabled: true }}>
          <Form.Item name="name" label="通道名称" rules={[{ required: true, message: '请输入通道名称' }]}>
            <Input prefix={<CheckCircleOutlined />} placeholder="例如：安全运营 Webhook、企业微信、飞书" />
          </Form.Item>

          <Form.Item name="channelType" label="通道类型">
            <Select options={CHANNEL_TYPE_OPTIONS} disabled={Boolean(editingRow)} />
          </Form.Item>

          <Form.Item
            name="webhookUrl"
            label={endpointLabel}
            rules={[
              { required: !editingRow, message: `请输入${endpointLabel}` },
              {
                validator: async (_, value?: string) => {
                  if (!value) {
                    return;
                  }
                  const trimmed = value.trim();
                  if (!trimmed) {
                    throw new Error('请输入有效通道地址，或留空保留现有密钥');
                  }
                  try {
                    new URL(trimmed);
                  } catch {
                    throw new Error('请输入合法 URL，例如 https://example.com/webhook');
                  }
                },
              },
            ]}
            extra={endpointExtra}
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

      <Modal
        title="执行选中密钥回填"
        open={backfillConfirmOpen}
        onOk={executeSelectedBackfill}
        onCancel={() => {
          setBackfillConfirmOpen(false);
          setBackfillConfirmationInput('');
        }}
        confirmLoading={backfillExecuteLoading}
        okButtonProps={{ disabled: backfillConfirmationInput !== BACKFILL_CONFIRMATION }}
        okText="执行选中迁移"
        destroyOnHidden
      >
        <Alert
          className="form-hint"
          type="warning"
          showIcon
          message="该操作会把选中的 legacy plaintext endpoint 加密迁移到 secret storage。成功后会清空这些通道的 endpoint_url。该操作不会清理历史 config_json 或 notification_deliveries。"
        />
        <Typography.Paragraph>
          已选择 {selectedBackfillChannelIds.length} 个通道。请输入确认短语以继续。
        </Typography.Paragraph>
        <Input
          value={backfillConfirmationInput}
          onChange={(event) => setBackfillConfirmationInput(event.target.value)}
          placeholder={BACKFILL_CONFIRMATION}
        />
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
