import {
  BellOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  DatabaseOutlined,
  LinkOutlined,
  NotificationOutlined,
  ReloadOutlined,
  RobotOutlined,
  WarningFilled,
} from '@ant-design/icons';
import { Button, Card, Progress, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useState } from 'react';
import { apiGet } from '../api';
import type {
  OverviewAlertRow,
  OverviewData,
  OverviewLifecycleEventRow,
  OverviewNotificationDeliveryRow,
} from '../types';
import {
  formatBusinessTime,
  getAlertStatus,
  getChannelTypeLabel,
  getFailureReasonLabel,
  getSeverityColor,
  getSeverityLabel,
} from '../utils/businessDisplay';

type DashboardNavigationKey = 'alerts' | 'notifications' | 'sources' | 'aiAgents';

interface DashboardPageProps {
  onNavigate?: (page: DashboardNavigationKey) => void;
}

const emptyOverview: OverviewData = {
  requestTime: '',
  dataSources: {
    total: 0,
    healthy: 0,
    abnormal: 0,
    unchecked: 0,
    enabled: 0,
    disabled: 0,
    healthRate: 0,
  },
  schema: {
    tables: 0,
    fields: 0,
    mappings: 0,
    confirmedTables: 0,
    mappedRate: 0,
  },
  rules: {
    total: 0,
    enabled: 0,
    disabled: 0,
    enabledRate: 0,
  },
  alerts: {
    open: 0,
    today: 0,
    yesterday: 0,
    delta: 0,
    bySeverity: {},
    byStatus: {},
    trend: [],
    recent: [],
  },
  reports: {
    total: 0,
    completed: 0,
    running: 0,
    failed: 0,
    pending: 0,
    byStatus: {},
  },
  recentDataSources: [],
  securityOperations: {
    totalAlerts: 0,
    openAlerts: 0,
    acknowledgedAlerts: 0,
    closedAlerts: 0,
    highRiskAlerts: 0,
    todayAlerts: 0,
  },
  notificationDelivery: {
    todayTotal: 0,
    todaySuccess: 0,
    todayFailed: 0,
    todaySuccessRate: 0,
    retryableFailed: 0,
    byFailureType: {},
    recentFailed: [],
  },
  notificationChannels: {
    total: 0,
    enabled: 0,
    disabled: 0,
    byType: {},
  },
  recentLifecycleEvents: [],
};

function normalizeOverview(payload?: Partial<OverviewData> | null): OverviewData {
  const data = payload ?? {};
  return {
    ...emptyOverview,
    ...data,
    dataSources: { ...emptyOverview.dataSources, ...data.dataSources },
    schema: { ...emptyOverview.schema, ...data.schema },
    rules: { ...emptyOverview.rules, ...data.rules },
    alerts: {
      ...emptyOverview.alerts,
      ...data.alerts,
      bySeverity: { ...emptyOverview.alerts.bySeverity, ...data.alerts?.bySeverity },
      byStatus: { ...emptyOverview.alerts.byStatus, ...data.alerts?.byStatus },
      trend: data.alerts?.trend ?? emptyOverview.alerts.trend,
      recent: data.alerts?.recent ?? emptyOverview.alerts.recent,
    },
    reports: {
      ...emptyOverview.reports,
      ...data.reports,
      byStatus: { ...emptyOverview.reports.byStatus, ...data.reports?.byStatus },
    },
    recentDataSources: data.recentDataSources ?? emptyOverview.recentDataSources,
    securityOperations: {
      ...emptyOverview.securityOperations,
      ...data.securityOperations,
    },
    notificationDelivery: {
      ...emptyOverview.notificationDelivery,
      ...data.notificationDelivery,
      byFailureType: {
        ...emptyOverview.notificationDelivery.byFailureType,
        ...data.notificationDelivery?.byFailureType,
      },
      recentFailed: data.notificationDelivery?.recentFailed ?? emptyOverview.notificationDelivery.recentFailed,
    },
    notificationChannels: {
      ...emptyOverview.notificationChannels,
      ...data.notificationChannels,
      byType: { ...emptyOverview.notificationChannels.byType, ...data.notificationChannels?.byType },
    },
    recentLifecycleEvents: data.recentLifecycleEvents ?? emptyOverview.recentLifecycleEvents,
  };
}

function countFrom(source: Record<string, number> | undefined, keys: string[]) {
  const normalized = new Map<string, number>();
  Object.entries(source ?? {}).forEach(([key, value]) => normalized.set(key.toLowerCase(), value));
  return keys.reduce((sum, key) => sum + (normalized.get(key.toLowerCase()) ?? 0), 0);
}

function numberFrom(source: Record<string, unknown>, keys: string[], fallback = 0) {
  for (const key of keys) {
    const value = source[key];
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value;
    }
    if (typeof value === 'string' && value.trim() !== '') {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) {
        return parsed;
      }
    }
  }
  return fallback;
}

function toDate(value?: string | number) {
  if (!value) {
    return null;
  }
  const normalizedValue = typeof value === 'number' && value < 100000000000 ? value * 1000 : value;
  const date = new Date(normalizedValue);
  return Number.isNaN(date.getTime()) ? null : date;
}

function formatDateTime(value?: string | number) {
  if (!value) {
    return '暂无数据';
  }
  const date = toDate(value);
  if (!date) {
    return String(value);
  }
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatTime(value?: string | number) {
  return formatBusinessTime(value);
}

function severityLabel(severity?: string) {
  return getSeverityLabel(severity);
}

function severityColor(severity?: string) {
  return getSeverityColor(severity);
}

function alertStatusLabel(status?: string) {
  return getAlertStatus(status).label;
}

function alertStatusColor(status?: string) {
  return getAlertStatus(status).color;
}

function channelTypeLabel(value?: string) {
  return getChannelTypeLabel(value);
}

function lifecycleActionLabel(value?: string) {
  switch (value?.toLowerCase()) {
    case 'acknowledged':
    case 'acknowledge':
      return '确认';
    case 'assigned':
    case 'assign':
      return '指派';
    case 'closed':
    case 'close':
      return '关闭';
    default:
      return value || '-';
  }
}

function lifecycleAlertTitle(row: OverviewLifecycleEventRow) {
  return row.alertTitle ?? row.alert_title ?? '告警待命名';
}

function lifecycleOperator(row: OverviewLifecycleEventRow) {
  return row.operatorName ?? row.operator_name ?? '-';
}

function lifecycleCreatedAt(row: OverviewLifecycleEventRow) {
  return row.createdAt ?? row.created_at;
}

function deliveryCreatedAt(row: OverviewNotificationDeliveryRow) {
  return row.created_at;
}

function deliveryFailureTypeLabel(value?: string | null) {
  return getFailureReasonLabel(value);
}

const alertColumns: ColumnsType<OverviewAlertRow> = [
  {
    title: '告警名称',
    dataIndex: 'title',
    render: (title: string, row) => (
      <div>
        <strong>{title}</strong>
        <span className="table-subtext">{row.subject_ref || row.subject_type || '未关联对象'}</span>
      </div>
    ),
  },
  {
    title: '等级',
    dataIndex: 'severity',
    width: 90,
    render: (level: string) => <Tag color={severityColor(level)}>{severityLabel(level)}</Tag>,
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 100,
    render: (status: string) => <Tag color={alertStatusColor(status)}>{alertStatusLabel(status)}</Tag>,
  },
  { title: '发生时间', dataIndex: 'created_at', width: 140, align: 'right', render: formatTime },
];

const lifecycleColumns: ColumnsType<OverviewLifecycleEventRow> = [
  {
    title: '告警',
    render: (_, row) => (
      <div>
        <strong>{lifecycleAlertTitle(row)}</strong>
        <span className="table-subtext">告警记录已关联</span>
      </div>
    ),
  },
  {
    title: '动作',
    width: 90,
    render: (_, row) => <Tag>{lifecycleActionLabel(row.eventType ?? row.event_type)}</Tag>,
  },
  {
    title: '操作者',
    width: 110,
    render: (_, row) => lifecycleOperator(row),
  },
  {
    title: '时间',
    width: 140,
    align: 'right',
    render: (_, row) => formatTime(lifecycleCreatedAt(row)),
  },
];

const failedDeliveryColumns: ColumnsType<OverviewNotificationDeliveryRow> = [
  {
    title: '失败通知',
    dataIndex: 'title',
    render: (title: string, row) => (
      <div>
        <strong>{title}</strong>
        <span className="table-subtext">{row.alert_title || row.channel_name || '通知记录待命名'}</span>
      </div>
    ),
  },
  {
    title: '通道',
    width: 110,
    render: (_, row) => channelTypeLabel(row.channel_type),
  },
  {
    title: '失败类型',
    dataIndex: 'failure_type',
    width: 130,
    render: (value: string | null) => <Tag color={value ? 'error' : 'default'}>{deliveryFailureTypeLabel(value)}</Tag>,
  },
  {
    title: '原因',
    dataIndex: 'failure_reason',
    ellipsis: true,
    render: (value: string | null) => value || '-',
  },
  {
    title: '可重试',
    dataIndex: 'retryable',
    width: 90,
    render: (value: boolean) => <Tag color={value ? 'warning' : 'default'}>{value ? '可重试' : '不重试'}</Tag>,
  },
  {
    title: '次数',
    dataIndex: 'retry_count',
    width: 76,
    align: 'right',
  },
  {
    title: '时间',
    width: 130,
    align: 'right',
    render: (_, row) => formatTime(deliveryCreatedAt(row)),
  },
];

function FailureTypeSummary({ data }: { data: Record<string, number> }) {
  const rows = Object.entries(data).slice(0, 5);
  if (rows.length === 0) {
    return <Typography.Text type="secondary">暂无失败类型</Typography.Text>;
  }
  return (
    <div className="stat-list">
      {rows.map(([type, count]) => (
        <span key={type}>
          <i className="dot orange" />
          {getFailureReasonLabel(type)}
          <b>{count}</b>
        </span>
      ))}
    </div>
  );
}

function ChannelTypeSummary({ data }: { data: Record<string, number> }) {
  const rows = Object.entries(data);
  if (rows.length === 0) {
    return <Typography.Text type="secondary">暂无通道</Typography.Text>;
  }
  return (
    <Space size={[8, 8]} wrap>
      {rows.map(([type, count]) => (
        <Tag key={type} color="processing">
          {channelTypeLabel(type)} {count}
        </Tag>
      ))}
    </Space>
  );
}

export function DashboardPage({ onNavigate }: DashboardPageProps) {
  const [overview, setOverview] = useState<OverviewData>(emptyOverview);
  const [loading, setLoading] = useState(false);

  const loadOverview = async () => {
    setLoading(true);
    try {
      const data = await apiGet<Partial<OverviewData>>('/api/core/overview');
      setOverview(normalizeOverview(data));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadOverview();
  }, []);

  const highRisks = countFrom(overview.alerts.bySeverity, ['critical', 'high', '高危']);
  const mediumRisks = countFrom(overview.alerts.bySeverity, ['medium', '中危']);
  const lowRisks = countFrom(overview.alerts.bySeverity, ['low', '低危']);
  const securityOps = overview.securityOperations as unknown as Record<string, unknown>;
  const deliveryStats = overview.notificationDelivery as unknown as Record<string, unknown>;
  const channelStats = overview.notificationChannels as unknown as Record<string, unknown>;
  const acknowledgedAlerts =
    numberFrom(securityOps, ['acknowledgedAlerts', 'acknowledged', 'acknowledged_alerts']) ||
    countFrom(overview.alerts.byStatus, ['acknowledged', '已确认']);
  const closedAlerts =
    numberFrom(securityOps, ['closedAlerts', 'closed', 'closed_alerts']) ||
    countFrom(overview.alerts.byStatus, ['closed', 'resolved', 'done', '已关闭', '已恢复']);
  const openAlerts =
    numberFrom(securityOps, ['openAlerts', 'open', 'open_alerts']) ||
    countFrom(overview.alerts.byStatus, ['open', 'pending', '未处理']) ||
    overview.alerts.open;
  const handledAlerts = acknowledgedAlerts + closedAlerts;
  const statusTotal =
    numberFrom(securityOps, ['totalAlerts', 'total', 'total_alerts']) ||
    openAlerts + handledAlerts ||
    overview.alerts.open + overview.alerts.today;
  const handlingRate = statusTotal > 0 ? Math.round((handledAlerts / statusTotal) * 100) : 0;
  const highRiskAlerts = numberFrom(securityOps, ['highRiskAlerts', 'high_risk_alerts']) || highRisks;
  const todayAlerts = numberFrom(securityOps, ['todayAlerts', 'today_alerts']) || overview.alerts.today;

  const deliveryTotal = numberFrom(deliveryStats, ['todayTotal', 'today_total']);
  const deliverySuccess = numberFrom(deliveryStats, ['todaySuccess', 'today_success']);
  const deliveryFailed = numberFrom(deliveryStats, ['todayFailed', 'today_failed']);
  const deliverySuccessRate = numberFrom(deliveryStats, ['todaySuccessRate', 'today_success_rate']);
  const retryableFailed = numberFrom(deliveryStats, ['retryableFailed', 'retryableFailedCount', 'retryable_failed']);
  const deliveryFailures = overview.notificationDelivery.recentFailed;
  const failureTypes = overview.notificationDelivery.byFailureType;

  const channels = overview.notificationChannels;
  const channelEnabled = numberFrom(channelStats, ['enabled', 'enabledCount', 'enabled_count']);
  const channelDisabled = numberFrom(channelStats, ['disabled', 'disabledCount', 'disabled_count']);
  const channelTotal =
    numberFrom(channelStats, ['total', 'totalCount', 'total_count']) ||
    channelEnabled + channelDisabled;
  const channelEnabledRate = channelTotal > 0 ? Math.round((channelEnabled / channelTotal) * 100) : 0;

  return (
    <div className="ops-dashboard">
      <div className="ops-heading">
        <div>
          <Typography.Title level={3}>安全运营看板</Typography.Title>
          <Typography.Text type="secondary">告警、处置和通知投递的只读运行概览</Typography.Text>
          <div className="ops-updated">更新时间：{formatDateTime(overview.requestTime)}</div>
        </div>
        <Button icon={<ReloadOutlined />} onClick={loadOverview} loading={loading}>
          刷新
        </Button>
      </div>

      <section className="ops-metric-grid">
        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>告警总览</span>
            <BellOutlined />
          </div>
          <div className="number-split">
            <div>
              <strong>{openAlerts}</strong>
              <span>开放告警</span>
            </div>
            <div className="mini-legend">
              <span><i className="dot red" />高危 {highRiskAlerts}</span>
              <span><i className="dot orange" />中危 {mediumRisks}</span>
              <span><i className="dot yellow" />低危 {lowRisks}</span>
              <span>今日新增 {todayAlerts}</span>
            </div>
          </div>
        </Card>

        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>处置概览</span>
            <CheckCircleFilled />
          </div>
          <div className="operation-panel">
            <div>
              <strong>{handlingRate}%</strong>
              <span>已处理 {handledAlerts} / {statusTotal}</span>
            </div>
            <Progress percent={handlingRate} showInfo={false} strokeColor="#0f9f9a" />
            <div className="operation-status-list">
              <span>已确认 <b>{acknowledgedAlerts}</b></span>
              <span>已关闭 <b>{closedAlerts}</b></span>
              <span>开放 <b>{openAlerts}</b></span>
            </div>
          </div>
        </Card>

        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>通知投递概览</span>
            <NotificationOutlined />
          </div>
          <div className="operation-panel">
            <div>
              <strong>{deliverySuccessRate}%</strong>
              <span>成功 {deliverySuccess} / {deliveryTotal}</span>
            </div>
            <Progress percent={deliverySuccessRate} showInfo={false} strokeColor="#17b77a" />
            <div className="operation-status-list">
              <span>失败 <b>{deliveryFailed}</b></span>
              <span>可重试失败 <b>{retryableFailed}</b></span>
              <span>今日总数 <b>{deliveryTotal}</b></span>
            </div>
          </div>
        </Card>

        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>通道状态</span>
            <LinkOutlined />
          </div>
          <div className="operation-panel">
            <div>
              <strong>{channelEnabledRate}%</strong>
              <span>启用 {channelEnabled} / {channelTotal}</span>
            </div>
            <Progress percent={channelEnabledRate} showInfo={false} strokeColor="#0f9f9a" />
            <div className="operation-status-list">
              <span>停用 <b>{channelDisabled}</b></span>
              <span>Webhook <b>{channels.byType.webhook ?? 0}</b></span>
              <span>飞书 <b>{channels.byType.feishu ?? 0}</b></span>
            </div>
          </div>
        </Card>
      </section>

      <section className="ops-table-grid">
        <Card
          className="ops-card"
          title="最近告警"
          extra={<Button type="link" onClick={() => onNavigate?.('alerts')}>进入告警中心</Button>}
        >
          <Table
            rowKey="id"
            columns={alertColumns}
            dataSource={overview.alerts.recent}
            size="small"
            loading={loading}
            pagination={false}
            locale={{ emptyText: '暂无告警' }}
          />
        </Card>

        <Card className="ops-card" title="最近处置动态">
          <Table
            rowKey={(row, index) => String(row.id ?? `${row.alertId ?? row.alert_id ?? 'alert'}-${index}`)}
            columns={lifecycleColumns}
            dataSource={overview.recentLifecycleEvents}
            size="small"
            loading={loading}
            pagination={false}
            locale={{ emptyText: '暂无处置动态' }}
          />
        </Card>
      </section>

      <section className="ops-bottom-grid">
        <Card className="ops-card" title="数据源健康">
          <div className="operation-panel">
            <div>
              <strong>{overview.dataSources.healthRate}%</strong>
              <span>健康 {overview.dataSources.healthy} / {overview.dataSources.total}</span>
            </div>
            <Progress percent={overview.dataSources.healthRate} showInfo={false} strokeColor="#17b77a" />
            <div className="operation-status-list">
              <span>异常 <b>{overview.dataSources.abnormal}</b></span>
              <span>未检测 <b>{overview.dataSources.unchecked}</b></span>
              <span>停用 <b>{overview.dataSources.disabled}</b></span>
            </div>
          </div>
        </Card>

        <Card
          className="ops-card"
          title="最近失败通知"
          extra={<Button type="link" onClick={() => onNavigate?.('notifications')}>进入通知中心</Button>}
        >
          <Table
            rowKey="id"
            columns={failedDeliveryColumns}
            dataSource={deliveryFailures}
            size="small"
            loading={loading}
            pagination={false}
            locale={{ emptyText: '暂无失败通知' }}
          />
        </Card>

        <Card className="ops-card" title="快捷入口">
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <ChannelTypeSummary data={channels.byType} />
            <FailureTypeSummary data={failureTypes} />
            <Button block icon={<BellOutlined />} onClick={() => onNavigate?.('alerts')}>
              告警中心
            </Button>
            <Button block icon={<NotificationOutlined />} onClick={() => onNavigate?.('notifications')}>
              通知中心
            </Button>
            <Button block icon={<DatabaseOutlined />} onClick={() => onNavigate?.('sources')}>
              数据源管理
            </Button>
            <Button block icon={<RobotOutlined />} onClick={() => onNavigate?.('aiAgents')}>
              AI 运营建议
            </Button>
            <div className="stat-list">
              <span><WarningFilled /> 待处置 <b>{openAlerts}</b></span>
              <span><ClockCircleOutlined /> 投递失败 <b>{deliveryFailed}</b></span>
              <span><DatabaseOutlined /> 数据源异常 <b>{overview.dataSources.abnormal}</b></span>
            </div>
          </Space>
        </Card>
      </section>
    </div>
  );
}
