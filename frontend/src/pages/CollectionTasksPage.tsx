import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloudSyncOutlined,
  DatabaseOutlined,
  FieldTimeOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Progress,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost, apiPut } from '../api';
import type {
  CollectionTaskRow,
  DataSourceRow,
  IngestionRunRow,
  RawEventRow,
  Severity,
  StandardEventRow,
} from '../types';

interface TaskFormValues {
  dataSourceId: number;
  name: string;
  taskType: string;
  scheduleMode: string;
  intervalSeconds: number;
  status: string;
  enabled: boolean;
  configJson?: string;
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

function statusTag(value?: string) {
  if (value === 'running') {
    return <Tag color="processing" icon={<SyncOutlined spin />}>运行中</Tag>;
  }
  if (value === 'success' || value === 'idle' || value === 'active') {
    return <Tag color="success" icon={<CheckCircleOutlined />}>正常</Tag>;
  }
  if (value === 'failed' || value === 'error') {
    return <Tag color="error" icon={<WarningOutlined />}>异常</Tag>;
  }
  if (value === 'paused' || value === 'disabled') {
    return <Tag icon={<PauseCircleOutlined />}>已暂停</Tag>;
  }
  if (value === 'draft') {
    return <Tag>草稿</Tag>;
  }
  return <Tag color="warning" icon={<ClockCircleOutlined />}>{value || '等待'}</Tag>;
}

function sourceTypeLabel(value?: string) {
  const labels: Record<string, string> = {
    sqlserver: 'SQL Server',
    mssql: 'SQL Server',
    mysql: 'MySQL',
    postgresql: 'PostgreSQL',
    oracle: 'Oracle',
    http_api: 'HTTP API',
    webhook: 'Webhook',
    file_import: '文件导入',
    security_platform: '安全平台',
    database: '数据库',
  };
  return value ? labels[value] || value : '-';
}

function taskTypeLabel(value?: string) {
  const labels: Record<string, string> = {
    pull: '主动拉取',
    webhook: '实时接收',
    file: '文件导入',
    database_log: '数据库日志',
    manual: '手动任务',
  };
  return value ? labels[value] || value : '-';
}

function scheduleLabel(row: CollectionTaskRow) {
  if (row.schedule_mode === 'realtime') {
    return '实时';
  }
  if (row.schedule_mode === 'manual') {
    return '手动';
  }
  return `${row.interval_seconds || 0} 秒`;
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

function taskDefaults(source?: DataSourceRow): Partial<TaskFormValues> {
  const kind = source?.connection_kind || source?.source_type || 'database';
  const realtime = kind === 'webhook';
  return {
    dataSourceId: source?.id,
    name: source ? `${source.name} 预警采集` : '',
    taskType: realtime ? 'webhook' : kind === 'file' ? 'file' : 'pull',
    scheduleMode: realtime ? 'realtime' : 'interval',
    intervalSeconds: realtime ? 0 : 300,
    status: 'idle',
    enabled: true,
    configJson: '{}',
  };
}

export default function CollectionTasksPage() {
  const [sources, setSources] = useState<DataSourceRow[]>([]);
  const [tasks, setTasks] = useState<CollectionTaskRow[]>([]);
  const [runs, setRuns] = useState<IngestionRunRow[]>([]);
  const [rawEvents, setRawEvents] = useState<RawEventRow[]>([]);
  const [standardEvents, setStandardEvents] = useState<StandardEventRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [taskOpen, setTaskOpen] = useState(false);
  const [form] = Form.useForm<TaskFormValues>();

  async function load() {
    setLoading(true);
    try {
      const [sourceRows, taskRows, runRows, rawRows, standardRows] = await Promise.all([
        apiGet<DataSourceRow[]>('/api/core/data-sources'),
        apiGet<CollectionTaskRow[]>('/api/core/collection-tasks'),
        apiGet<IngestionRunRow[]>('/api/core/collection-tasks/runs?limit=80'),
        apiGet<RawEventRow[]>('/api/core/ingestion/raw-events?limit=80'),
        apiGet<StandardEventRow[]>('/api/core/ingestion/standard-events?limit=80'),
      ]);
      setSources(sourceRows);
      setTasks(taskRows);
      setRuns(runRows);
      setRawEvents(rawRows);
      setStandardEvents(standardRows);
    } catch (error) {
      setSources([]);
      setTasks([]);
      setRuns([]);
      setRawEvents([]);
      setStandardEvents([]);
      message.warning(error instanceof Error ? error.message : '采集链路接口暂不可用');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function openCreateTask() {
    if (sources.length === 0) {
      message.warning('请先在数据源管理中新增外部系统接入');
      return;
    }
    form.setFieldsValue(taskDefaults(sources[0]));
    setTaskOpen(true);
  }

  function handleSourceChange(sourceId: number) {
    form.setFieldsValue(taskDefaults(sources.find((source) => source.id === sourceId)));
  }

  async function saveTask() {
    const values = await form.validateFields();
    try {
      JSON.parse(values.configJson || '{}');
    } catch {
      message.error('任务参数必须是合法 JSON');
      return;
    }

    setSaving(true);
    try {
      await apiPost('/api/core/collection-tasks', {
        dataSourceId: values.dataSourceId,
        adapterId: null,
        name: values.name,
        taskType: values.taskType,
        scheduleMode: values.scheduleMode,
        intervalSeconds: values.scheduleMode === 'realtime' ? 1 : values.intervalSeconds,
        status: values.status,
        enabled: values.enabled,
        configJson: values.configJson || '{}',
      });
      message.success('采集任务已创建');
      setTaskOpen(false);
      form.resetFields();
      await load();
    } finally {
      setSaving(false);
    }
  }

  async function startRun(row: CollectionTaskRow) {
    const result = await apiPost<{
      readCount?: number;
      successCount?: number;
      standardizedCount?: number;
      failedCount?: number;
      status?: string;
    }>(`/api/core/collection-tasks/${row.id}/runs?runType=manual`, {});
    const readCount = result.readCount ?? 0;
    const standardizedCount = result.standardizedCount ?? result.successCount ?? 0;
    const failedCount = result.failedCount ?? 0;
    if (result.status === 'failed' || failedCount > 0) {
      message.warning(`采集完成但存在失败：读取 ${readCount} 条，标准化 ${standardizedCount} 条，失败 ${failedCount} 条`);
    } else {
      message.success(`采集完成：读取 ${readCount} 条，标准化 ${standardizedCount} 条`);
    }
    await load();
  }

  async function finishRun(row: IngestionRunRow) {
    await apiPut(`/api/core/collection-tasks/runs/${row.id}/finish`, {
      status: 'success',
      cursorAfter: `manual:${new Date().toISOString()}`,
      readCount: row.read_count || 0,
      successCount: row.success_count || row.read_count || 0,
      failedCount: row.failed_count || 0,
      skippedCount: row.skipped_count || 0,
      errorMessage: null,
      qualityReportJson: '{}',
    });
    message.success('运行记录已标记完成');
    await load();
  }

  const sourceOptions = sources.map((source) => ({
    value: source.id,
    label: `${source.name} / ${sourceTypeLabel(source.source_type)}`,
  }));

  const runningCount = tasks.filter((task) => task.status === 'running').length;
  const failedCount = tasks.filter((task) => task.status === 'failed').length;
  const successRuns = runs.filter((run) => run.status === 'success').length;
  const runSuccessRate = runs.length ? Math.round((successRuns / runs.length) * 100) : 0;

  const taskColumns: ColumnsType<CollectionTaskRow> = [
    {
      title: '采集任务',
      dataIndex: 'name',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">
            {row.data_source_name} / {sourceTypeLabel(row.source_type)}
          </span>
        </div>
      ),
    },
    { title: '任务类型', dataIndex: 'task_type', width: 120, render: taskTypeLabel },
    { title: '适配器', dataIndex: 'adapter_name', width: 180, render: (value) => value || '通用采集适配器' },
    { title: '调度', width: 110, render: (_, row) => scheduleLabel(row) },
    { title: '最近运行', dataIndex: 'last_run_at', width: 140, render: formatTime },
    { title: '下次运行', dataIndex: 'next_run_at', width: 140, render: formatTime },
    { title: '状态', dataIndex: 'status', width: 110, render: statusTag },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 90,
      render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作',
      width: 110,
      align: 'right',
      render: (_, row) => (
        <Button size="small" icon={<PlayCircleOutlined />} onClick={() => startRun(row)}>
          执行
        </Button>
      ),
    },
  ];

  const runColumns: ColumnsType<IngestionRunRow> = [
    {
      title: '运行任务',
      dataIndex: 'task_name',
      render: (value: string, row) => (
        <div>
          <strong>{value || `任务 #${row.task_id || '-'}`}</strong>
          <span className="table-subtext">{row.data_source_name || '-'}</span>
        </div>
      ),
    },
    { title: '类型', dataIndex: 'run_type', width: 100 },
    { title: '读取', dataIndex: 'read_count', align: 'right', width: 90 },
    { title: '成功', dataIndex: 'success_count', align: 'right', width: 90 },
    { title: '失败', dataIndex: 'failed_count', align: 'right', width: 90 },
    { title: '跳过', dataIndex: 'skipped_count', align: 'right', width: 90 },
    { title: '开始时间', dataIndex: 'started_at', width: 150, render: formatTime },
    { title: '完成时间', dataIndex: 'finished_at', width: 150, render: formatTime },
    { title: '状态', dataIndex: 'status', width: 110, render: statusTag },
    {
      title: '操作',
      width: 110,
      align: 'right',
      render: (_, row) =>
        row.status === 'running' ? (
          <Button size="small" icon={<CheckCircleOutlined />} onClick={() => finishRun(row)}>
            完成
          </Button>
        ) : null,
    },
  ];

  const rawColumns: ColumnsType<RawEventRow> = [
    { title: '接收时间', dataIndex: 'received_at', width: 150, render: formatTime },
    {
      title: '来源',
      dataIndex: 'data_source_name',
      render: (value: string, row) => (
        <div>
          <strong>{value || row.source_system || '-'}</strong>
          <span className="table-subtext">{row.source_system || '-'}</span>
        </div>
      ),
    },
    { title: '外部 ID', dataIndex: 'external_id', width: 180, render: (value) => value || '-' },
    { title: '事件类型', dataIndex: 'event_type', width: 150, render: (value) => value || '-' },
    { title: '发生时间', dataIndex: 'occurred_at', width: 150, render: formatTime },
    { title: '状态', dataIndex: 'status', width: 120, render: statusTag },
    {
      title: '标准事件',
      dataIndex: 'standard_event_id',
      width: 110,
      render: (value?: number) => (value ? <Tag color="success">#{value}</Tag> : <Tag>待标准化</Tag>),
    },
  ];

  const standardColumns: ColumnsType<StandardEventRow> = [
    {
      title: '标准事件',
      dataIndex: 'event_type',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">
            {row.source_system} / {row.external_id || '-'}
          </span>
        </div>
      ),
    },
    {
      title: '对象',
      dataIndex: 'actor',
      width: 220,
      render: (value: string, row) => (
        <div>
          <strong>{value || '-'}</strong>
          <span className="table-subtext">{row.asset_ref || row.subject_ref || '-'}</span>
        </div>
      ),
    },
    { title: '动作', dataIndex: 'action', width: 120, render: (value) => value || '-' },
    { title: '结果', dataIndex: 'result', width: 100, render: (value) => value || '-' },
    { title: '等级', dataIndex: 'severity', width: 100, render: (value) => <Tag color={severityColor(value)}>{severityLabel(value)}</Tag> },
    { title: '风险分', dataIndex: 'risk_score', align: 'right', width: 90 },
    { title: '发生时间', dataIndex: 'occurred_at', width: 150, render: formatTime },
  ];

  return (
    <div className="collection-page">
      <div className="ops-heading">
        <div>
          <h3 className="ant-typography">采集任务</h3>
          <span>统一管理心跳检测、增量采集、实时接收、游标和原始事件标准化链路</span>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateTask}>
            新增任务
          </Button>
        </Space>
      </div>

      <div className="collection-summary-grid">
        <Card className="ops-card">
          <Statistic title="采集任务" value={tasks.length} prefix={<DatabaseOutlined />} />
        </Card>
        <Card className="ops-card">
          <Statistic title="运行中" value={runningCount} prefix={<CloudSyncOutlined />} valueStyle={{ color: '#137c72' }} />
        </Card>
        <Card className="ops-card">
          <Statistic title="原始事件" value={rawEvents.length} prefix={<FieldTimeOutlined />} />
        </Card>
        <Card className="ops-card">
          <Statistic title="标准事件" value={standardEvents.length} prefix={<SafetyCertificateOutlined />} />
        </Card>
      </div>

      <div className="collection-detail-grid">
        <Card className="ops-card" title="采集链路">
          <Tabs
            items={[
              {
                key: 'tasks',
                label: '采集任务',
                children: (
                  <Table<CollectionTaskRow>
                    rowKey="id"
                    loading={loading}
                    dataSource={tasks}
                    columns={taskColumns}
                    pagination={{ pageSize: 8 }}
                    scroll={{ x: 1180 }}
                    locale={{ emptyText: '暂无采集任务。先新增数据源，再创建对应采集任务。' }}
                  />
                ),
              },
              {
                key: 'runs',
                label: '运行记录',
                children: (
                  <Table<IngestionRunRow>
                    rowKey="id"
                    loading={loading}
                    dataSource={runs}
                    columns={runColumns}
                    pagination={{ pageSize: 8 }}
                    scroll={{ x: 1040 }}
                    locale={{ emptyText: '暂无运行记录。执行采集任务后会写入这里。' }}
                  />
                ),
              },
              {
                key: 'raw',
                label: 'Raw 事件',
                children: (
                  <Table<RawEventRow>
                    rowKey="id"
                    loading={loading}
                    dataSource={rawEvents}
                    columns={rawColumns}
                    pagination={{ pageSize: 8 }}
                    scroll={{ x: 1020 }}
                    locale={{ emptyText: '暂无 Raw 事件。采集适配器写入原始事件后会展示在这里。' }}
                  />
                ),
              },
              {
                key: 'standard',
                label: '标准事件',
                children: (
                  <Table<StandardEventRow>
                    rowKey="id"
                    loading={loading}
                    dataSource={standardEvents}
                    columns={standardColumns}
                    pagination={{ pageSize: 8 }}
                    scroll={{ x: 1040 }}
                    locale={{ emptyText: '暂无标准事件。Raw 事件标准化后会进入统一事件模型。' }}
                  />
                ),
              },
            ]}
          />
        </Card>

        <div className="collection-side-panel">
          <Card className="ops-card" title="任务健康度">
            <Progress type="dashboard" percent={runSuccessRate} status={failedCount ? 'exception' : 'normal'} />
            <div className="task-health-list">
              <span><b>异常任务</b>{failedCount}</span>
              <span><b>运行记录</b>{runs.length}</span>
              <span><b>成功运行</b>{successRuns}</span>
            </div>
          </Card>

          <Card className="ops-card" title="字段变化处理">
            <Alert
              type="info"
              showIcon
              message="采集任务不直接要求客户手工输入字段。字段变化由元数据快照自动发现，普通字段自动纳入，疑似敏感或影响规则的字段再进入确认。"
            />
            <div className="schema-drift-flow">
              <span><FieldTimeOutlined /> 采集时发现新结构</span>
              <span><SafetyCertificateOutlined /> 自动分类与风险判断</span>
              <span><CloudSyncOutlined /> 更新元数据快照和映射建议</span>
            </div>
          </Card>
        </div>
      </div>

      <Modal
        width={760}
        title="新增采集任务"
        open={taskOpen}
        onOk={saveTask}
        onCancel={() => setTaskOpen(false)}
        okText="保存"
        confirmLoading={saving}
        destroyOnHidden
      >
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="这里创建的是正式采集链路配置。具体适配器可以后续按数据库、API、Webhook、文件或安全平台扩展。"
        />
        <Form<TaskFormValues> form={form} layout="vertical">
          <Form.Item name="dataSourceId" label="外部系统数据源" rules={[{ required: true, message: '请选择数据源' }]}>
            <Select options={sourceOptions} onChange={handleSourceChange} />
          </Form.Item>
          <Form.Item name="name" label="任务名称" rules={[{ required: true, message: '请输入任务名称' }]}>
            <Input placeholder="例如：DLP 告警增量采集" />
          </Form.Item>
          <Space className="metadata-form-grid" align="start">
            <Form.Item name="taskType" label="任务类型" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'pull', label: '主动拉取' },
                  { value: 'webhook', label: '实时接收' },
                  { value: 'file', label: '文件导入' },
                  { value: 'database_log', label: '数据库日志' },
                ]}
              />
            </Form.Item>
            <Form.Item name="scheduleMode" label="调度方式" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'manual', label: '手动' },
                  { value: 'interval', label: '周期执行' },
                  { value: 'realtime', label: '实时接收' },
                ]}
              />
            </Form.Item>
          </Space>
          <Space className="metadata-form-grid" align="start">
            <Form.Item name="intervalSeconds" label="执行间隔（秒）">
              <InputNumber min={1} max={86400} />
            </Form.Item>
            <Form.Item name="status" label="初始状态">
              <Select
                options={[
                  { value: 'idle', label: '正常待调度' },
                  { value: 'draft', label: '草稿' },
                  { value: 'paused', label: '暂停' },
                ]}
              />
            </Form.Item>
          </Space>
          <Form.Item name="configJson" label="任务参数 JSON">
            <Input.TextArea rows={4} placeholder='例如：{"cursorField":"updated_at","batchSize":500}' />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
        <Typography.Text type="secondary">
          后续真正落地时，这里会由适配器模板自动带出必填项，客户不需要理解底层字段名。
        </Typography.Text>
      </Modal>
    </div>
  );
}
