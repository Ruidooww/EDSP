import {
  ApiOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SafetyCertificateOutlined,
  TableOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost } from '../api';
import type { DataSourceRow } from '../types';

type SourceType =
  | 'sqlserver'
  | 'mysql'
  | 'postgresql'
  | 'oracle'
  | 'webhook'
  | 'http_api'
  | 'file_import'
  | 'security_platform';

type ConnectionKind = 'database' | 'webhook' | 'api' | 'file' | 'security_platform';

interface SourceTemplate {
  value: SourceType;
  label: string;
  kind: ConnectionKind;
  summary: string;
  status: 'implemented' | 'configured';
}

interface DataSourceFormValues {
  name: string;
  sourceType: SourceType;
  host?: string;
  port?: number;
  database?: string;
  username?: string;
  password?: string;
  endpointUrl?: string;
  method?: string;
  authType?: string;
  token?: string;
  headerName?: string;
  secret?: string;
  fileFormat?: string;
  watchPath?: string;
  schedule?: string;
  vendor?: string;
  product?: string;
  baseUrl?: string;
  pollingInterval?: number;
  description?: string;
  encrypt?: boolean;
  trustServerCertificate?: boolean;
  enabled: boolean;
}

interface ConnectionTestResult {
  id?: number;
  status: string;
  message: string;
  sourceType?: string;
  connectionKind?: string;
  host?: string;
  port?: number;
  database?: string;
  currentDatabase?: string;
  productName?: string;
  productVersion?: string;
  databases?: string[];
  tables?: string[];
}

interface SqlTableRow {
  schema_name: string;
  table_name: string;
  row_count: number;
  create_date?: string;
  modify_date?: string;
}

interface SqlColumnRow {
  column_id: number;
  column_name: string;
  data_type: string;
  max_length: number;
  precision: number;
  scale: number;
  is_nullable: boolean;
}

interface TablesResponse {
  status: string;
  message?: string;
  database: string;
  keyword: string;
  tables: SqlTableRow[];
}

interface ColumnsResponse {
  status: string;
  message?: string;
  database: string;
  schema: string;
  table: string;
  columns: SqlColumnRow[];
}

const SOURCE_TEMPLATES: SourceTemplate[] = [
  {
    value: 'sqlserver',
    label: 'SQL Server 数据库',
    kind: 'database',
    summary: '适合本地化系统数据库、审计库、日志库接入',
    status: 'implemented',
  },
  {
    value: 'mysql',
    label: 'MySQL 数据库',
    kind: 'database',
    summary: '用于接入业务库、审计库、日志库中的告警相关数据',
    status: 'configured',
  },
  {
    value: 'postgresql',
    label: 'PostgreSQL 数据库',
    kind: 'database',
    summary: '用于接入 OA、堡垒机、审计平台等系统数据库',
    status: 'configured',
  },
  {
    value: 'oracle',
    label: 'Oracle 数据库',
    kind: 'database',
    summary: '用于接入核心业务系统、审计库和历史日志库',
    status: 'configured',
  },
  {
    value: 'webhook',
    label: 'Webhook 推送接入',
    kind: 'webhook',
    summary: '第三方系统主动把预警推送到平台',
    status: 'configured',
  },
  {
    value: 'http_api',
    label: 'HTTP API 拉取接入',
    kind: 'api',
    summary: '平台按周期调用外部 API 拉取预警',
    status: 'configured',
  },
  {
    value: 'file_import',
    label: '文件批量导入',
    kind: 'file',
    summary: '通过 CSV、JSON 文件导入历史或离线预警',
    status: 'configured',
  },
  {
    value: 'security_platform',
    label: '第三方安全平台',
    kind: 'security_platform',
    summary: '对接 UEBA、DLP、终端安全、审计平台等系统',
    status: 'configured',
  },
];

const SOURCE_TEMPLATE_MAP = SOURCE_TEMPLATES.reduce<Record<string, SourceTemplate>>((acc, item) => {
  acc[item.value] = item;
  return acc;
}, {});

const CONNECTION_KIND_LABEL: Record<string, string> = {
  database: '数据库',
  webhook: 'Webhook 推送',
  api: 'API 拉取',
  file: '文件导入',
  security_platform: '安全平台',
};

const SOURCE_TYPE_LABEL = SOURCE_TEMPLATES.reduce<Record<string, string>>((acc, item) => {
  acc[item.value] = item.label;
  return acc;
}, {});

function sourceTemplate(sourceType?: string) {
  return SOURCE_TEMPLATE_MAP[sourceType || 'sqlserver'] || SOURCE_TEMPLATE_MAP.sqlserver;
}

function isSqlServerSource(row: Pick<DataSourceRow, 'source_type'>) {
  return ['sqlserver', 'mssql'].includes(String(row.source_type).toLowerCase());
}

function isDatabaseTemplate(sourceType?: string) {
  return sourceTemplate(sourceType).kind === 'database';
}

function statusTag(status: string) {
  if (status === 'active') {
    return <Tag color="success">已连接</Tag>;
  }
  if (status === 'configured') {
    return <Tag color="processing">已配置</Tag>;
  }
  if (status === 'error') {
    return <Tag color="error">连接异常</Tag>;
  }
  return <Tag>未检测</Tag>;
}

function templateStatusTag(template: SourceTemplate) {
  if (template.status === 'implemented') {
    return <Tag color="success">可连接测试</Tag>;
  }
  return <Tag color="processing">配置型接入</Tag>;
}

function sourceTypeLabel(value: string) {
  return SOURCE_TYPE_LABEL[value] || value;
}

function connectionKindLabel(value: string) {
  return CONNECTION_KIND_LABEL[value] || value;
}

function defaultValues(sourceType: SourceType = 'sqlserver'): Partial<DataSourceFormValues> {
  const template = sourceTemplate(sourceType);
  const base = {
    sourceType,
    name: template.label,
    enabled: true,
    encrypt: false,
    trustServerCertificate: true,
  };

  if (template.kind === 'database') {
    return {
      ...base,
      port: sourceType === 'sqlserver' ? 1433 : sourceType === 'mysql' ? 3306 : sourceType === 'postgresql' ? 5432 : 1521,
      database: sourceType === 'sqlserver' ? 'master' : '',
    };
  }
  if (template.kind === 'webhook') {
    return {
      ...base,
      endpointUrl: '/api/alerts/ingest',
      authType: 'bearer',
      headerName: 'Authorization',
    };
  }
  if (template.kind === 'api') {
    return {
      ...base,
      method: 'GET',
      authType: 'bearer',
      pollingInterval: 60,
    };
  }
  if (template.kind === 'file') {
    return {
      ...base,
      fileFormat: 'json',
      schedule: 'manual',
    };
  }
  return {
    ...base,
    vendor: 'other',
    pollingInterval: 60,
  };
}

function removeEmpty(config: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(config).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  );
}

function buildConfig(values: DataSourceFormValues) {
  const template = sourceTemplate(values.sourceType);
  if (template.kind === 'database') {
    return removeEmpty({
      driver: values.sourceType,
      host: values.host,
      port: values.port,
      database: values.database,
      username: values.username,
      password: values.password,
      encrypt: values.encrypt ?? false,
      trustServerCertificate: values.trustServerCertificate ?? true,
    });
  }
  if (template.kind === 'webhook') {
    return removeEmpty({
      endpointUrl: values.endpointUrl,
      authType: values.authType,
      token: values.token,
      headerName: values.headerName,
      secret: values.secret,
      standardIngestPath: '/api/alerts/ingest',
    });
  }
  if (template.kind === 'api') {
    return removeEmpty({
      endpointUrl: values.endpointUrl,
      method: values.method || 'GET',
      authType: values.authType,
      token: values.token,
      pollingInterval: values.pollingInterval,
    });
  }
  if (template.kind === 'file') {
    return removeEmpty({
      fileFormat: values.fileFormat,
      watchPath: values.watchPath,
      schedule: values.schedule,
    });
  }
  return removeEmpty({
    vendor: values.vendor,
    product: values.product,
    baseUrl: values.baseUrl,
    authType: values.authType,
    token: values.token,
    pollingInterval: values.pollingInterval,
  });
}

function buildPayload(values: DataSourceFormValues) {
  const template = sourceTemplate(values.sourceType);
  return {
    name: values.name,
    sourceType: values.sourceType,
    connectionKind: template.kind,
    description: values.description,
    enabled: values.enabled ?? true,
    configJson: JSON.stringify(buildConfig(values)),
  };
}

function encode(value: string) {
  return encodeURIComponent(value.trim());
}

export default function DataSourcesPage() {
  const [rows, setRows] = useState<DataSourceRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);
  const [scanOpen, setScanOpen] = useState(false);
  const [scanLoading, setScanLoading] = useState(false);
  const [activeSourceId, setActiveSourceId] = useState<number | null>(null);
  const [scanDatabase, setScanDatabase] = useState('master');
  const [scanKeyword, setScanKeyword] = useState('');
  const [scanRows, setScanRows] = useState<SqlTableRow[]>([]);
  const [columnRows, setColumnRows] = useState<SqlColumnRow[]>([]);
  const [selectedTable, setSelectedTable] = useState('');
  const [form] = Form.useForm<DataSourceFormValues>();
  const selectedSourceType = Form.useWatch('sourceType', form) || 'sqlserver';
  const selectedTemplate = useMemo(() => sourceTemplate(selectedSourceType), [selectedSourceType]);

  async function load() {
    setLoading(true);
    try {
      setRows(await apiGet<DataSourceRow[]>('/api/core/data-sources'));
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function openCreateModal() {
    setOpen(true);
    setTestResult(null);
    form.setFieldsValue(defaultValues('sqlserver'));
  }

  function handleTemplateChange(value: SourceType) {
    setTestResult(null);
    form.setFieldsValue(defaultValues(value));
  }

  async function testCurrentForm() {
    const values = await form.validateFields();
    setTesting(true);
    try {
      const result = await apiPost<ConnectionTestResult>('/api/core/data-sources/test', buildPayload(values));
      setTestResult(result);
      if (result.status === 'active') {
        message.success('连接测试成功');
      } else if (result.status === 'configured') {
        message.success('接入配置校验通过');
      } else {
        message.warning(result.message || '连接测试失败');
      }
    } finally {
      setTesting(false);
    }
  }

  async function testSaved(row: DataSourceRow) {
    setLoading(true);
    try {
      const result = await apiPost<ConnectionTestResult>(`/api/core/data-sources/${row.id}/test`, {});
      if (result.status === 'active') {
        message.success('连接测试成功');
      } else if (result.status === 'configured') {
        message.success('接入配置已确认');
      } else {
        message.warning(result.message || '连接测试失败');
      }
      await load();
    } finally {
      setLoading(false);
    }
  }

  async function submit() {
    const values = await form.validateFields();
    await apiPost('/api/core/data-sources', buildPayload(values));
    message.success('数据源配置已保存');
    setOpen(false);
    setTestResult(null);
    form.resetFields();
    await load();
  }

  function openScan(row: DataSourceRow) {
    setActiveSourceId(row.id);
    setScanOpen(true);
    setScanRows([]);
    setColumnRows([]);
    setSelectedTable('');
  }

  async function scanTables() {
    if (!activeSourceId) {
      return;
    }
    setScanLoading(true);
    setColumnRows([]);
    setSelectedTable('');
    try {
      const result = await apiGet<TablesResponse>(
        `/api/core/data-sources/${activeSourceId}/tables?database=${encode(scanDatabase)}&keyword=${encode(scanKeyword)}&limit=150`,
      );
      if (result.status !== 'active') {
        message.warning(result.message || '扫描失败');
        setScanRows([]);
        return;
      }
      setScanRows(result.tables);
    } finally {
      setScanLoading(false);
    }
  }

  async function loadColumns(row: SqlTableRow) {
    if (!activeSourceId) {
      return;
    }
    setScanLoading(true);
    try {
      const result = await apiGet<ColumnsResponse>(
        `/api/core/data-sources/${activeSourceId}/columns?database=${encode(scanDatabase)}&schema=${encode(row.schema_name)}&table=${encode(row.table_name)}`,
      );
      if (result.status !== 'active') {
        message.warning(result.message || '字段读取失败');
        setColumnRows([]);
        return;
      }
      setSelectedTable(`${row.schema_name}.${row.table_name}`);
      setColumnRows(result.columns);
    } finally {
      setScanLoading(false);
    }
  }

  const columns: ColumnsType<DataSourceRow> = [
    { title: '接入名称', dataIndex: 'name' },
    {
      title: '接入模板',
      dataIndex: 'source_type',
      render: (value: string) => <Tag color="blue">{sourceTypeLabel(value)}</Tag>,
    },
    {
      title: '接入方式',
      dataIndex: 'connection_kind',
      render: (value: string) => connectionKindLabel(value),
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: statusTag,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      render: (value) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作',
      align: 'right',
      render: (_, row) => (
        <Space>
          <Button size="small" icon={<ThunderboltOutlined />} onClick={() => testSaved(row)}>
            {isSqlServerSource(row) ? '测试连接' : '确认配置'}
          </Button>
          {isSqlServerSource(row) && (
            <Button size="small" icon={<TableOutlined />} onClick={() => openScan(row)}>
              扫描库表
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const tableColumns: ColumnsType<SqlTableRow> = [
    { title: 'Schema', dataIndex: 'schema_name', width: 110 },
    { title: '表名', dataIndex: 'table_name' },
    { title: '行数', dataIndex: 'row_count', align: 'right', width: 120 },
    {
      title: '操作',
      align: 'right',
      width: 100,
      render: (_, row) => (
        <Button size="small" type="link" onClick={() => loadColumns(row)}>
          字段
        </Button>
      ),
    },
  ];

  const columnColumns: ColumnsType<SqlColumnRow> = [
    { title: '#', dataIndex: 'column_id', width: 70 },
    { title: '字段名', dataIndex: 'column_name' },
    { title: '类型', dataIndex: 'data_type', width: 120 },
    { title: '长度', dataIndex: 'max_length', align: 'right', width: 90 },
    {
      title: '可空',
      dataIndex: 'is_nullable',
      width: 90,
      render: (value) => <Tag>{value ? '是' : '否'}</Tag>,
    },
  ];

  return (
    <Card
      className="dashboard-card"
      title="数据源管理"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新增接入
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph className="section-subtitle">
        统一管理本地化系统、数据库、API、Webhook、文件导入和第三方安全平台的接入配置。
      </Typography.Paragraph>

      <Table<DataSourceRow>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        locale={{ emptyText: '暂无外部系统接入配置，可以先新增一个接入源。' }}
      />

      <Modal
        width={820}
        title="新增数据源"
        open={open}
        onOk={submit}
        onCancel={() => setOpen(false)}
        okText="保存"
        destroyOnHidden
      >
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="平台会保存接入配置，账号密码不会在列表中展示。不同接入模板可用于数据库、API、Webhook、文件和安全平台。"
        />
        <Form layout="vertical" form={form} initialValues={defaultValues('sqlserver')}>
          <Form.Item name="sourceType" label="接入模板" rules={[{ required: true, message: '请选择接入模板' }]}>
            <Select
              onChange={handleTemplateChange}
              options={SOURCE_TEMPLATES.map((item) => ({
                value: item.value,
                label: item.label,
              }))}
            />
          </Form.Item>

          <div className="source-template-card">
            <Space align="start">
              {isDatabaseTemplate(selectedSourceType) ? (
                <DatabaseOutlined className="source-template-icon" />
              ) : selectedTemplate.kind === 'file' ? (
                <FileTextOutlined className="source-template-icon" />
              ) : selectedTemplate.kind === 'security_platform' ? (
                <SafetyCertificateOutlined className="source-template-icon" />
              ) : (
                <LinkOutlined className="source-template-icon" />
              )}
              <div>
                <Space wrap>
                  <strong>{selectedTemplate.label}</strong>
                  {templateStatusTag(selectedTemplate)}
                </Space>
                <div className="template-summary">{selectedTemplate.summary}</div>
              </div>
            </Space>
          </div>

          <Form.Item name="name" label="接入名称" rules={[{ required: true, message: '请输入接入名称' }]}>
            <Input placeholder="例如：本地终端安全系统、DLP 告警 API、OA 审计库" />
          </Form.Item>

          {selectedTemplate.kind === 'database' && (
            <>
              <Space className="form-grid-2" align="start">
                <Form.Item name="host" label="IP / 主机" rules={[{ required: true, message: '请输入主机地址' }]}>
                  <Input prefix={<ApiOutlined />} placeholder="例如：192.168.1.10" />
                </Form.Item>
                <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
                  <InputNumber min={1} max={65535} />
                </Form.Item>
              </Space>

              <Space className="form-grid-2" align="start">
                <Form.Item name="database" label="默认数据库">
                  <Input placeholder="不知道库名时可先填写默认库，例如 master" />
                </Form.Item>
                <Form.Item name="username" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
                  <Input placeholder="例如：sa、readonly_user" />
                </Form.Item>
              </Space>

              <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
                <Input.Password placeholder="请输入密码" />
              </Form.Item>

              <Space size={24}>
                <Form.Item name="encrypt" label="启用加密" valuePropName="checked">
                  <Switch />
                </Form.Item>
                <Form.Item name="trustServerCertificate" label="信任服务器证书" valuePropName="checked">
                  <Switch />
                </Form.Item>
              </Space>
            </>
          )}

          {selectedTemplate.kind === 'webhook' && (
            <>
              <Form.Item name="endpointUrl" label="平台接收地址" rules={[{ required: true, message: '请输入接收地址' }]}>
                <Input prefix={<LinkOutlined />} placeholder="/api/alerts/ingest" />
              </Form.Item>
              <Space className="form-grid-2" align="start">
                <Form.Item name="authType" label="认证方式">
                  <Select
                    options={[
                      { value: 'none', label: '无认证' },
                      { value: 'bearer', label: 'Bearer Token' },
                      { value: 'signature', label: '签名密钥' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="headerName" label="认证 Header">
                  <Input placeholder="Authorization" />
                </Form.Item>
              </Space>
              <Form.Item name="secret" label="密钥 / Token">
                <Input.Password placeholder="用于校验外部系统推送请求" />
              </Form.Item>
            </>
          )}

          {selectedTemplate.kind === 'api' && (
            <>
              <Form.Item name="endpointUrl" label="外部 API 地址" rules={[{ required: true, message: '请输入 API 地址' }]}>
                <Input prefix={<LinkOutlined />} placeholder="https://example.com/openapi/alerts" />
              </Form.Item>
              <Space className="form-grid-2" align="start">
                <Form.Item name="method" label="请求方法">
                  <Select
                    options={[
                      { value: 'GET', label: 'GET' },
                      { value: 'POST', label: 'POST' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="pollingInterval" label="采集间隔（秒）">
                  <InputNumber min={10} max={86400} />
                </Form.Item>
              </Space>
              <Space className="form-grid-2" align="start">
                <Form.Item name="authType" label="认证方式">
                  <Select
                    options={[
                      { value: 'none', label: '无认证' },
                      { value: 'bearer', label: 'Bearer Token' },
                      { value: 'basic', label: 'Basic Auth' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="token" label="Token / 凭据">
                  <Input.Password placeholder="外部 API 访问凭据" />
                </Form.Item>
              </Space>
            </>
          )}

          {selectedTemplate.kind === 'file' && (
            <>
              <Space className="form-grid-2" align="start">
                <Form.Item name="fileFormat" label="文件格式" rules={[{ required: true, message: '请选择文件格式' }]}>
                  <Select
                    options={[
                      { value: 'json', label: 'JSON' },
                      { value: 'csv', label: 'CSV' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="schedule" label="导入方式">
                  <Select
                    options={[
                      { value: 'manual', label: '手动导入' },
                      { value: 'scheduled', label: '定时扫描目录' },
                    ]}
                  />
                </Form.Item>
              </Space>
              <Form.Item name="watchPath" label="文件目录 / 上传说明">
                <Input placeholder="例如：/data/security-alert/imports 或 由管理员手动上传" />
              </Form.Item>
            </>
          )}

          {selectedTemplate.kind === 'security_platform' && (
            <>
              <Space className="form-grid-2" align="start">
                <Form.Item name="vendor" label="平台厂商 / 类型" rules={[{ required: true, message: '请输入平台类型' }]}>
                  <Select
                    options={[
                      { value: 'ueba', label: 'UEBA' },
                      { value: 'dlp', label: 'DLP' },
                      { value: 'edr', label: '终端安全 / EDR' },
                      { value: 'audit', label: '审计平台' },
                      { value: 'other', label: '其他' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="product" label="产品名称">
                  <Input placeholder="例如：IP-guard、OA 审计、终端安全平台" />
                </Form.Item>
              </Space>
              <Form.Item name="baseUrl" label="平台地址 / API 地址">
                <Input prefix={<LinkOutlined />} placeholder="https://example.com 或 数据库/接口说明" />
              </Form.Item>
              <Space className="form-grid-2" align="start">
                <Form.Item name="authType" label="认证方式">
                  <Select
                    options={[
                      { value: 'none', label: '无认证' },
                      { value: 'bearer', label: 'Bearer Token' },
                      { value: 'basic', label: 'Basic Auth' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="pollingInterval" label="采集间隔（秒）">
                  <InputNumber min={10} max={86400} />
                </Form.Item>
              </Space>
              <Form.Item name="token" label="Token / 凭据">
                <Input.Password placeholder="外部平台访问凭据" />
              </Form.Item>
            </>
          )}

          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="记录来源系统、负责人、接入阶段、数据范围等信息" />
          </Form.Item>

          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Button icon={<CheckCircleOutlined />} loading={testing} onClick={testCurrentForm}>
            {selectedTemplate.status === 'implemented' ? '先测试连接' : '校验配置'}
          </Button>
        </Form>

        {testResult && (
          <Descriptions className="connection-result" size="small" bordered column={1}>
            <Descriptions.Item label="结果">
              {statusTag(testResult.status)} {testResult.message}
            </Descriptions.Item>
            <Descriptions.Item label="接入类型">{sourceTypeLabel(testResult.sourceType || selectedSourceType)}</Descriptions.Item>
            <Descriptions.Item label="接入方式">
              {connectionKindLabel(testResult.connectionKind || selectedTemplate.kind)}
            </Descriptions.Item>
            <Descriptions.Item label="当前数据库">{testResult.currentDatabase || testResult.database || '-'}</Descriptions.Item>
            <Descriptions.Item label="数据库版本">{testResult.productVersion || '-'}</Descriptions.Item>
            <Descriptions.Item label="可用数据库">{testResult.databases?.join(', ') || '-'}</Descriptions.Item>
            <Descriptions.Item label="当前库表预览">{testResult.tables?.join(', ') || '-'}</Descriptions.Item>
          </Descriptions>
        )}
      </Modal>

      <Modal width={1040} title="扫描库表" open={scanOpen} onCancel={() => setScanOpen(false)} footer={null} destroyOnHidden>
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="库表扫描用于数据库类接入；API、Webhook、文件和安全平台通过对应采集适配器读取预警。"
        />
        <Space className="scan-toolbar" wrap>
          <Input
            prefix={<DatabaseOutlined />}
            value={scanDatabase}
            onChange={(event) => setScanDatabase(event.target.value)}
            placeholder="例如：master、audit_log 或 alert_center"
          />
          <Input
            prefix={<SearchOutlined />}
            value={scanKeyword}
            onChange={(event) => setScanKeyword(event.target.value)}
            placeholder="按表名过滤，例如 ALERT / LOG / POLICY"
            allowClear
          />
          <Button type="primary" icon={<TableOutlined />} loading={scanLoading} onClick={scanTables}>
            扫描
          </Button>
        </Space>

        <div className="scan-grid">
          <Table<SqlTableRow>
            rowKey={(row) => `${row.schema_name}.${row.table_name}`}
            size="small"
            loading={scanLoading}
            dataSource={scanRows}
            columns={tableColumns}
            pagination={{ pageSize: 8 }}
            locale={{ emptyText: '输入库名后点击扫描' }}
          />
          <Card size="small" title={selectedTable ? `字段：${selectedTable}` : '字段'}>
            <Table<SqlColumnRow>
              rowKey="column_id"
              size="small"
              loading={scanLoading}
              dataSource={columnRows}
              columns={columnColumns}
              pagination={{ pageSize: 8 }}
              locale={{ emptyText: '点击左侧表的“字段”查看结果' }}
            />
          </Card>
        </div>
      </Modal>
    </Card>
  );
}
