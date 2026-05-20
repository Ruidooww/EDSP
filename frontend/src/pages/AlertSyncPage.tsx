import {
  ApiOutlined,
  BellOutlined,
  CheckCircleOutlined,
  CloudSyncOutlined,
  DatabaseOutlined,
  FieldTimeOutlined,
  LinkOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SendOutlined,
  SyncOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Button, Card, Collapse, Form, InputNumber, Select, Space, Steps, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost } from '../api';
import type { AlertRow, DataSourceRow, NotificationChannelRow, Severity } from '../types';

interface IntegrationFormValues {
  dataSourceId: number;
  adapter: string;
  eventDatabase: string;
  identityDatabase: string;
  tableLimit: number;
  rowLimit: number;
}

interface ConnectionTestResult {
  status: string;
  message: string;
  databases?: string[];
  productVersion?: string;
}

interface SyncResult {
  status: string;
  created: number;
  updated: number;
  skipped: number;
  scannedRows: number;
  message?: string;
}

function encode(value: string | number) {
  return encodeURIComponent(String(value));
}

function severityColor(value?: Severity | string) {
  return {
    critical: 'red',
    high: 'red',
    medium: 'orange',
    low: 'gold',
    info: 'cyan',
  }[value ?? ''] ?? 'default';
}

function severityLabel(value?: Severity | string) {
  return {
    critical: '严重',
    high: '高危',
    medium: '中危',
    low: '低危',
    info: '提示',
  }[value ?? ''] ?? (value || '-');
}

function statusTag(status?: string) {
  if (status === 'active' || status === 'success') {
    return <Tag color="success">正常</Tag>;
  }
  if (status === 'error') {
    return <Tag color="error">异常</Tag>;
  }
  return <Tag>未检测</Tag>;
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

function isDemoSource(source?: DataSourceRow) {
  return !!source && (source.name.toLowerCase().includes('demo') || source.source_type === 'database');
}

export default function AlertSyncPage() {
  const [form] = Form.useForm<IntegrationFormValues>();
  const [sources, setSources] = useState<DataSourceRow[]>([]);
  const [databases, setDatabases] = useState<string[]>([]);
  const [alerts, setAlerts] = useState<AlertRow[]>([]);
  const [channels, setChannels] = useState<NotificationChannelRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [checking, setChecking] = useState(false);
  const [collecting, setCollecting] = useState(false);
  const [heartbeat, setHeartbeat] = useState<ConnectionTestResult | null>(null);
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
  const [lastHeartbeatAt, setLastHeartbeatAt] = useState('');
  const [lastCollectAt, setLastCollectAt] = useState('');

  async function loadBaseData() {
    setLoading(true);
    try {
      const [sourceRows, alertRows, channelRows] = await Promise.all([
        apiGet<DataSourceRow[]>('/api/core/data-sources'),
        apiGet<AlertRow[]>('/api/alerts'),
        apiGet<NotificationChannelRow[]>('/api/notifications/channels'),
      ]);
      setSources(sourceRows);
      setAlerts(alertRows);
      setChannels(channelRows);

      const activeSource = sourceRows.find((row) => row.enabled && row.status === 'active') ?? sourceRows[0];
      if (activeSource) {
        form.setFieldsValue({
          dataSourceId: activeSource.id,
          adapter: isDemoSource(activeSource) ? 'demo-alert-adapter' : 'sqlserver-omen',
        });
        await loadDatabases(activeSource.id, activeSource);
      }
    } catch {
      setSources([]);
      setAlerts([]);
      setChannels([]);
    } finally {
      setLoading(false);
    }
  }

  async function loadDatabases(sourceId: number, sourceOverride?: DataSourceRow) {
    const source = sourceOverride ?? sources.find((row) => row.id === sourceId);
    if (isDemoSource(source)) {
      const names = ['security_event', 'asset_user_snapshot'];
      setDatabases(names);
      setHeartbeat({
        status: 'active',
        message: 'Demo 外部系统心跳正常',
        productVersion: 'Demo Security Event DB',
        databases: names,
      });
      setLastHeartbeatAt(new Date().toLocaleString('zh-CN'));
      form.setFieldsValue({
        adapter: 'demo-alert-adapter',
        eventDatabase: 'security_event',
        identityDatabase: 'asset_user_snapshot',
      });
      return;
    }

    const result = await apiPost<ConnectionTestResult>(`/api/core/data-sources/${sourceId}/test`, {});
    const names = result.databases ?? [];
    setDatabases(names);
    setHeartbeat(result);
    setLastHeartbeatAt(new Date().toLocaleString('zh-CN'));
    form.setFieldsValue({
      eventDatabase: names.includes('OCULAR3_REPORT2') ? 'OCULAR3_REPORT2' : names[0],
      identityDatabase: names.includes('OCULAR3') ? 'OCULAR3' : names[0],
    });
  }

  useEffect(() => {
    form.setFieldsValue({
      adapter: 'sqlserver-omen',
      eventDatabase: 'OCULAR3_REPORT2',
      identityDatabase: 'OCULAR3',
      tableLimit: 50,
      rowLimit: 50,
    });
    void loadBaseData();
  }, [form]);

  async function checkHeartbeat() {
    const { dataSourceId } = await form.validateFields(['dataSourceId']);
    setChecking(true);
    try {
      await loadDatabases(dataSourceId);
      message.success(isDemoSource(sources.find((row) => row.id === dataSourceId)) ? 'Demo 心跳检测正常' : '数据库心跳检测正常');
    } finally {
      setChecking(false);
    }
  }

  async function collectAlerts() {
    const values = await form.validateFields();
    setCollecting(true);
    try {
      const source = sources.find((row) => row.id === values.dataSourceId);
      if (isDemoSource(source)) {
        const data = {
          status: 'success',
          created: 0,
          updated: 3,
          skipped: 0,
          scannedRows: 3,
          message: 'Demo 采集完成',
        };
        setSyncResult(data);
        setLastCollectAt(new Date().toLocaleString('zh-CN'));
        setAlerts(await apiGet<AlertRow[]>('/api/alerts'));
        message.success('Demo 采集完成：读取 3 条标准告警');
        return;
      }

      const data = await apiPost<SyncResult>(
        `/api/ingest/sqlserver/omen/sync?dataSourceId=${encode(values.dataSourceId)}&database=${encode(values.eventDatabase)}&tableLimit=${encode(values.tableLimit)}&rowLimit=${encode(values.rowLimit)}`,
        {},
      );
      setSyncResult(data);
      setLastCollectAt(new Date().toLocaleString('zh-CN'));
      setAlerts(await apiGet<AlertRow[]>('/api/alerts'));
      if (data.status === 'success') {
        message.success(`采集完成：新增 ${data.created}，更新 ${data.updated}`);
      } else {
        message.warning(data.message || '采集未完成');
      }
    } finally {
      setCollecting(false);
    }
  }

  const sourceOptions = useMemo(
    () =>
      sources.map((row) => ({
        value: row.id,
        label: row.name,
      })),
    [sources],
  );

  const databaseOptions = useMemo(
    () =>
      databases.map((name) => ({
        value: name,
        label: name,
      })),
    [databases],
  );

  const selectedSource = sources.find((row) => row.id === form.getFieldValue('dataSourceId')) ?? sources[0];
  const selectedIsDemo = isDemoSource(selectedSource);
  const activeChannels = channels.filter((row) => row.enabled).length;
  const highRiskAlerts = alerts.filter((row) => row.severity === 'critical' || row.severity === 'high').length;
  const todayKey = new Date().toISOString().slice(0, 10);
  const todayAlerts = alerts.filter((row) => (row.occurred_at || row.created_at || '').slice(0, 10) === todayKey).length;

  const alertColumns: ColumnsType<AlertRow> = [
    {
      title: '告警',
      dataIndex: 'title',
      render: (title: string, row) => (
        <div>
          <strong>{title}</strong>
          <span className="table-subtext">
            {row.source_system || 'external'} / {row.alert_type || 'standard'}
          </span>
        </div>
      ),
    },
    {
      title: '等级',
      dataIndex: 'severity',
      width: 90,
      render: (value: Severity) => <Tag color={severityColor(value)}>{severityLabel(value)}</Tag>,
    },
    {
      title: '对象',
      dataIndex: 'actor',
      width: 210,
      render: (value: string, row) => (
        <div>
          <strong>{value || '-'}</strong>
          <span className="table-subtext">{row.asset_ref || row.subject_ref || '-'}</span>
        </div>
      ),
    },
    { title: '策略', dataIndex: 'policy_name', width: 200, render: (value) => value || '-' },
    { title: '发生时间', dataIndex: 'occurred_at', width: 150, render: formatTime },
    { title: '状态', dataIndex: 'status', width: 100, render: (value) => <Tag>{value || '-'}</Tag> },
  ];

  return (
    <div className="integration-page">
      <div className="ops-heading">
        <div>
          <h3 className="ant-typography">外部系统接入</h3>
          <span>连接本地化系统数据库，持续心跳检测、采集预警并推送通知</span>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={loadBaseData}>
            刷新
          </Button>
          <Button icon={<LinkOutlined />} loading={checking} onClick={checkHeartbeat}>
            心跳检测
          </Button>
          <Button type="primary" icon={<CloudSyncOutlined />} loading={collecting} onClick={collectAlerts}>
            立即采集
          </Button>
        </Space>
      </div>

      <div className="integration-status-grid">
        <Card className="ops-card integration-status-card">
          <span>外部系统</span>
          <strong>{selectedSource?.name || '未配置'}</strong>
          <small>{selectedSource?.source_type || '-'} / {selectedSource?.connection_kind || '-'}</small>
          <div>{statusTag(heartbeat?.status || selectedSource?.status)}</div>
        </Card>
        <Card className="ops-card integration-status-card">
          <span>数据库心跳</span>
          <strong>{lastHeartbeatAt || '-'}</strong>
          <small>{heartbeat?.productVersion || '等待检测'}</small>
          <div>{heartbeat?.status === 'active' ? <Tag color="success">可连接</Tag> : <Tag>未检测</Tag>}</div>
        </Card>
        <Card className="ops-card integration-status-card">
          <span>今日新增预警</span>
          <strong>{todayAlerts}</strong>
          <small>高风险 {highRiskAlerts} 条</small>
          <div><Tag color={highRiskAlerts > 0 ? 'error' : 'success'}>{highRiskAlerts > 0 ? '需关注' : '平稳'}</Tag></div>
        </Card>
        <Card className="ops-card integration-status-card">
          <span>通知通道</span>
          <strong>{activeChannels}</strong>
          <small>已启用通道</small>
          <div>{activeChannels > 0 ? <Tag color="success">可推送</Tag> : <Tag color="warning">待配置</Tag>}</div>
        </Card>
      </div>

      <Collapse
        className="advanced-config-collapse"
        items={[
          {
            key: 'collector-config',
            label: '高级采集配置',
            forceRender: true,
            children: (
              <>
                <Typography.Text className="advanced-config-note" type="secondary">
                  实施接入和排障时使用。日常运营只需关注上方状态、采集结果和最近标准告警。
                </Typography.Text>
                <Form form={form} layout="vertical" className="integration-form">
                  <Form.Item name="dataSourceId" label="外部系统数据源" rules={[{ required: true, message: '请选择数据源' }]}>
                    <Select
                      loading={loading}
                      options={sourceOptions}
                      placeholder="选择已连接的数据源"
                      suffixIcon={<DatabaseOutlined />}
                      onChange={(value) => loadDatabases(value)}
                    />
                  </Form.Item>
                  <Form.Item name="adapter" label="采集适配器" rules={[{ required: true }]}>
                    <Select
                      options={[
                        { value: 'demo-alert-adapter', label: '通用告警采集适配器' },
                        { value: 'sqlserver-omen', label: 'SQL Server 预警日志适配器' },
                      ]}
                      suffixIcon={<ApiOutlined />}
                    />
                  </Form.Item>
                  <Form.Item name="eventDatabase" label="预警/报表库" rules={[{ required: true, message: '请选择预警库' }]}>
                    <Select showSearch options={databaseOptions} optionFilterProp="label" />
                  </Form.Item>
                  <Form.Item name="identityDatabase" label="主数据/配置库" rules={[{ required: true, message: '请选择主数据库' }]}>
                    <Select showSearch options={databaseOptions} optionFilterProp="label" />
                  </Form.Item>
                  <Form.Item name="tableLimit" label="采集表上限" rules={[{ required: true }]}>
                    <InputNumber min={1} max={500} />
                  </Form.Item>
                  <Form.Item name="rowLimit" label="单表行数上限" rules={[{ required: true }]}>
                    <InputNumber min={1} max={10000} />
                  </Form.Item>
                </Form>
              </>
            ),
          },
        ]}
      />

      <div className="integration-main-grid">
        <Card className="ops-card" title="接入链路">
          <Steps
            direction="vertical"
            current={heartbeat?.status === 'active' ? 4 : 1}
            items={[
              {
                title: '连接外部数据库',
                description: selectedSource?.name || '等待配置数据源',
                icon: <DatabaseOutlined />,
              },
              {
                title: '心跳检测',
                description: lastHeartbeatAt ? `最近检测：${lastHeartbeatAt}` : '尚未执行',
                icon: <FieldTimeOutlined />,
              },
              {
                title: '采集预警',
                description: syncResult ? `读取 ${syncResult.scannedRows} 条，新增 ${syncResult.created}，更新 ${syncResult.updated}` : '等待采集',
                icon: <SyncOutlined />,
              },
              {
                title: '标准化入库',
                description: '转换为平台统一告警模型',
                icon: <SafetyCertificateOutlined />,
              },
              {
                title: '通知推送',
                description: activeChannels > 0 ? `已启用 ${activeChannels} 个通知通道` : '等待配置通知通道',
                icon: <SendOutlined />,
              },
            ]}
          />
        </Card>

        <Card className="ops-card" title="采集结果">
          <div className="integration-result-panel">
            <div>
              <span>最近采集</span>
              <strong>{lastCollectAt || '-'}</strong>
            </div>
            <div>
              <span>读取预警</span>
              <strong>{syncResult?.scannedRows ?? 0}</strong>
            </div>
            <div>
              <span>新增 / 更新</span>
              <strong>{syncResult ? `${syncResult.created} / ${syncResult.updated}` : '0 / 0'}</strong>
            </div>
            <div>
              <span>通道状态</span>
              <strong>{activeChannels > 0 ? '可推送' : '待配置'}</strong>
            </div>
          </div>
        </Card>
      </div>

      <Card className="ops-card" title="最近标准告警">
        <Table<AlertRow>
          rowKey="id"
          loading={loading || collecting}
          dataSource={alerts}
          columns={alertColumns}
          pagination={{ pageSize: 8 }}
          scroll={{ x: 980 }}
          locale={{ emptyText: '暂无标准告警。可以先执行一次采集验证链路。' }}
        />
      </Card>
    </div>
  );
}
