import {
  BellOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  DatabaseOutlined,
  FileDoneOutlined,
  LineChartOutlined,
  ReloadOutlined,
  SearchOutlined,
  WarningFilled,
} from '@ant-design/icons';
import { Badge, Button, Card, Input, Progress, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet } from '../api';
import type { OverviewAlertRow, OverviewData, OverviewDataSourceRow, OverviewTrendPoint } from '../types';

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
};

function countFrom(source: Record<string, number> | undefined, keys: string[]) {
  const normalized = new Map<string, number>();
  Object.entries(source ?? {}).forEach(([key, value]) => normalized.set(key.toLowerCase(), value));
  return keys.reduce((sum, key) => sum + (normalized.get(key.toLowerCase()) ?? 0), 0);
}

function conicGradient(parts: Array<{ value: number; color: string }>) {
  const total = parts.reduce((sum, part) => sum + Math.max(0, part.value), 0);
  if (total <= 0) {
    return 'conic-gradient(#e7edf6 0 100%)';
  }

  let cursor = 0;
  const segments = parts
    .filter((part) => part.value > 0)
    .map((part) => {
      const start = cursor;
      cursor += (part.value / total) * 100;
      return `${part.color} ${start}% ${cursor}%`;
    });
  return `conic-gradient(${segments.join(', ')})`;
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
  if (!value) {
    return '-';
  }
  const date = toDate(value);
  if (!date) {
    return String(value);
  }
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function sourceStatusGroup(status: string) {
  const normalized = status?.toLowerCase();
  if (['active', 'healthy', 'connected', 'ok', 'ready', 'configured'].includes(normalized)) {
    return 'healthy';
  }
  if (['error', 'failed', 'offline', 'abnormal'].includes(normalized)) {
    return 'abnormal';
  }
  return 'unchecked';
}

function sourceStatusMeta(status: string) {
  if (status?.toLowerCase() === 'configured') {
    return { label: '已配置', badge: 'processing' as const, color: 'processing' };
  }
  const group = sourceStatusGroup(status);
  if (group === 'healthy') {
    return { label: '健康', badge: 'success' as const, color: 'success' };
  }
  if (group === 'abnormal') {
    return { label: '异常', badge: 'error' as const, color: 'error' };
  }
  return { label: '未检测', badge: 'default' as const, color: 'default' };
}

function severityLabel(severity: string) {
  switch (severity?.toLowerCase()) {
    case 'critical':
    case 'high':
      return '高危';
    case 'medium':
      return '中危';
    case 'low':
      return '低危';
    case 'info':
    case 'tip':
      return '提示';
    default:
      return severity || '未分级';
  }
}

function severityColor(severity: string) {
  switch (severityLabel(severity)) {
    case '高危':
      return 'red';
    case '中危':
      return 'orange';
    case '低危':
      return 'gold';
    case '提示':
      return 'cyan';
    default:
      return 'default';
  }
}

function statusLabel(status: string) {
  switch (status?.toLowerCase()) {
    case 'open':
    case 'pending':
      return '未处理';
    case 'processing':
    case 'running':
      return '处理中';
    case 'closed':
    case 'resolved':
    case 'done':
      return '已确认';
    default:
      return status || '-';
  }
}

function MiniLineChart({ points }: { points: OverviewTrendPoint[] }) {
  const values = points.length ? points.map((point) => point.value) : [0, 0, 0, 0, 0, 0, 0];
  const width = 260;
  const height = 120;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const line = values
    .map((value, index) => {
      const x = 12 + (index / Math.max(1, values.length - 1)) * (width - 24);
      const y = height - 18 - ((value - min) / range) * (height - 38);
      return `${x},${y}`;
    })
    .join(' ');

  return (
    <svg className="ops-line-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="近 7 天告警趋势">
      <line x1="12" y1="24" x2="248" y2="24" />
      <line x1="12" y1="62" x2="248" y2="62" />
      <line x1="12" y1="100" x2="248" y2="100" />
      <polyline points={line} />
      {line.split(' ').map((point) => {
        const [x, y] = point.split(',');
        return <circle key={point} cx={x} cy={y} r="3" />;
      })}
    </svg>
  );
}

const sourceColumns: ColumnsType<OverviewDataSourceRow> = [
  {
    title: '数据源名称',
    dataIndex: 'name',
    render: (name: string, row) => (
      <Space>
        <span className="table-source-icon">
          <DatabaseOutlined />
        </span>
        <div>
          <strong>{name}</strong>
          <span className="table-subtext">{row.source_type}</span>
        </div>
      </Space>
    ),
  },
  { title: '类型', dataIndex: 'connection_kind' },
  {
    title: '健康状态',
    dataIndex: 'status',
    render: (status: string) => {
      const meta = sourceStatusMeta(status);
      return <Badge status={meta.badge} text={meta.label} />;
    },
  },
  { title: '最后检测时间', dataIndex: 'updated_at', align: 'right', render: formatTime },
];

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
    title: '告警等级',
    dataIndex: 'severity',
    render: (level: string) => <Tag color={severityColor(level)}>{severityLabel(level)}</Tag>,
  },
  { title: '发生时间', dataIndex: 'created_at', align: 'right', render: formatTime },
  { title: '状态', dataIndex: 'status', render: (status: string) => <Tag>{statusLabel(status)}</Tag> },
];

export function DashboardPage() {
  const [overview, setOverview] = useState<OverviewData>(emptyOverview);
  const [loading, setLoading] = useState(false);
  const [sourceSearch, setSourceSearch] = useState('');
  const [sourceStatus, setSourceStatus] = useState('all');

  const loadOverview = async () => {
    setLoading(true);
    try {
      setOverview(await apiGet<OverviewData>('/api/core/overview'));
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
  const infoRisks = countFrom(overview.alerts.bySeverity, ['info', 'tip', '提示']);
  const riskTotal = highRisks + mediumRisks + lowRisks + infoRisks || overview.alerts.open;
  const handledAlerts = countFrom(overview.alerts.byStatus, ['closed', 'resolved', 'done', '已确认', '已恢复']);
  const processingAlerts = countFrom(overview.alerts.byStatus, ['processing', 'running', '处理中']);
  const openAlerts = countFrom(overview.alerts.byStatus, ['open', 'pending', '未处理']) || overview.alerts.open;
  const statusTotal = openAlerts + processingAlerts + handledAlerts;
  const handledRate = statusTotal > 0 ? Math.round((handledAlerts / statusTotal) * 100) : 0;

  const filteredSources = useMemo(() => {
    const keyword = sourceSearch.trim().toLowerCase();
    return overview.recentDataSources.filter((source) => {
      const matchedKeyword =
        !keyword ||
        source.name.toLowerCase().includes(keyword) ||
        source.source_type.toLowerCase().includes(keyword) ||
        source.connection_kind.toLowerCase().includes(keyword);
      const matchedStatus = sourceStatus === 'all' || sourceStatusGroup(source.status) === sourceStatus;
      return matchedKeyword && matchedStatus;
    });
  }, [overview.recentDataSources, sourceSearch, sourceStatus]);

  return (
    <div className="ops-dashboard">
      <div className="ops-heading">
        <div>
          <Typography.Title level={3}>总览</Typography.Title>
          <Typography.Text type="secondary">平台运行总览与关键指标</Typography.Text>
          <div className="ops-updated">更新时间：{formatDateTime(overview.requestTime)}</div>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={loadOverview} loading={loading}>
            刷新
          </Button>
          <Button>自定义</Button>
        </Space>
      </div>

      <section className="ops-metric-grid">
        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>风险态势</span>
            <WarningFilled />
          </div>
          <div className="donut-row">
            <div
              className="ops-donut"
              style={{
                background: conicGradient([
                  { value: highRisks, color: '#ef4444' },
                  { value: mediumRisks, color: '#fb7c11' },
                  { value: lowRisks, color: '#f6bd16' },
                  { value: infoRisks, color: '#10a89e' },
                ]),
              }}
            >
              <div>
                <span>风险总数</span>
                <strong>{riskTotal}</strong>
              </div>
            </div>
            <div className="legend-list">
              <span><i className="dot red" />高危 <b>{highRisks}</b></span>
              <span><i className="dot orange" />中危 <b>{mediumRisks}</b></span>
              <span><i className="dot yellow" />低危 <b>{lowRisks}</b></span>
              <span><i className="dot teal" />提示 <b>{infoRisks}</b></span>
            </div>
          </div>
        </Card>

        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>数据源健康</span>
            <DatabaseOutlined />
          </div>
          <div className="donut-row compact">
            <div
              className="ops-donut small"
              style={{
                background: conicGradient([
                  { value: overview.dataSources.healthy, color: '#17b77a' },
                  { value: overview.dataSources.abnormal, color: '#ef4444' },
                  { value: overview.dataSources.unchecked, color: '#d8e0ea' },
                ]),
              }}
            >
              <div>
                <strong>{overview.dataSources.healthRate}%</strong>
                <span>健康度</span>
              </div>
            </div>
            <div className="stat-list">
              <span>总数 <b>{overview.dataSources.total}</b></span>
              <span>健康 <b>{overview.dataSources.healthy}</b></span>
              <span>异常 <b className="danger">{overview.dataSources.abnormal}</b></span>
              <span>未检测 <b>{overview.dataSources.unchecked}</b></span>
            </div>
          </div>
        </Card>

        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>开放告警</span>
            <BellOutlined />
          </div>
          <div className="number-split">
            <div>
              <strong>{overview.alerts.open}</strong>
              <span>较昨日 {overview.alerts.delta >= 0 ? '+' : ''}{overview.alerts.delta}</span>
            </div>
            <div className="mini-legend">
              <span><i className="dot red" />高危 {highRisks}</span>
              <span><i className="dot orange" />中危 {mediumRisks}</span>
              <span><i className="dot yellow" />低危 {lowRisks}</span>
              <span><i className="dot teal" />提示 {infoRisks}</span>
            </div>
          </div>
        </Card>

        <Card className="ops-card metric-card">
          <div className="card-title">
            <span>规则启用率</span>
            <LineChartOutlined />
          </div>
          <div className="rule-card">
            <strong>{overview.rules.enabledRate}%</strong>
            <span>启用规则 {overview.rules.enabled} / {overview.rules.total}</span>
            <MiniLineChart points={overview.alerts.trend} />
          </div>
        </Card>
      </section>

      <section className="ops-table-grid">
        <Card
          className="ops-card"
          title="数据源健康"
          extra={
            <Space>
              <Select
                value={sourceStatus}
                onChange={setSourceStatus}
                options={[
                  { value: 'all', label: '全部分组' },
                  { value: 'healthy', label: '健康' },
                  { value: 'abnormal', label: '异常' },
                  { value: 'unchecked', label: '未检测' },
                ]}
              />
              <Input
                prefix={<SearchOutlined />}
                placeholder="搜索数据源名称"
                value={sourceSearch}
                onChange={(event) => setSourceSearch(event.target.value)}
                allowClear
              />
            </Space>
          }
        >
          <Table
            rowKey="id"
            columns={sourceColumns}
            dataSource={filteredSources}
            size="small"
            loading={loading}
            pagination={{ pageSize: 6 }}
            locale={{ emptyText: '暂无数据源' }}
          />
        </Card>

        <Card className="ops-card" title="开放告警" extra={<Button type="link">全部告警</Button>}>
          <Table
            rowKey="id"
            columns={alertColumns}
            dataSource={overview.alerts.recent}
            size="small"
            loading={loading}
            pagination={false}
            locale={{ emptyText: '暂无开放告警' }}
          />
        </Card>
      </section>

      <section className="ops-bottom-grid">
        <Card className="ops-card" title="告警处置率">
          <div className="operation-panel">
            <div>
              <strong>{handledRate}%</strong>
              <span>已确认 {handledAlerts} / {statusTotal}</span>
            </div>
            <Progress percent={handledRate} showInfo={false} strokeColor="#0f9f9a" />
            <div className="operation-status-list">
              <span><i className="dot red" />未处理 <b>{openAlerts}</b></span>
              <span><i className="dot orange" />处理中 <b>{processingAlerts}</b></span>
              <span><i className="dot teal" />已确认 <b>{handledAlerts}</b></span>
            </div>
          </div>
        </Card>

        <Card className="ops-card" title="告警趋势（近 7 天）">
          <MiniLineChart points={overview.alerts.trend} />
        </Card>

        <Card className="ops-card" title="报表任务概况">
          <div className="report-panel">
            <div className="ops-donut small report">
              <div>
                <span>总任务数</span>
                <strong>{overview.reports.total}</strong>
              </div>
            </div>
            <div className="stat-list">
              <span><CheckCircleFilled /> 成功 <b>{overview.reports.completed}</b></span>
              <span><ClockCircleOutlined /> 运行中 <b>{overview.reports.running + overview.reports.pending}</b></span>
              <span><WarningFilled /> 失败 <b>{overview.reports.failed}</b></span>
            </div>
          </div>
        </Card>
      </section>
    </div>
  );
}
