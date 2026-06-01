import {
  ApiOutlined,
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  CheckCircleFilled,
  CloudSyncOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DownOutlined,
  FileExcelOutlined,
  LockOutlined,
  LoginOutlined,
  NotificationOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  SyncOutlined,
  TableOutlined,
  UserOutlined,
  WarningFilled,
} from '@ant-design/icons';
import { Avatar, Badge, Button, Card, Descriptions, Drawer, Dropdown, Form, Input, Layout, Menu, Modal, Segmented, Space, Table, Tag, Typography, message } from 'antd';
import { useMemo, useState } from 'react';
import type { MenuProps } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import loginHero from './assets/login-hero.png';
import AlertsPage from './pages/AlertsPage';
import AlertSyncPage from './pages/AlertSyncPage';
import CollectionTasksPage from './pages/CollectionTasksPage';
import { DashboardPage } from './pages/DashboardPage';
import DataSourcesPage from './pages/DataSourcesPage';
import NotificationsPage from './pages/NotificationsPage';
import ReportsPage from './pages/ReportsPage';
import AiAgentPage from './pages/AiAgentPage';
import RulesPage from './pages/RulesPage';
import SchemaPage from './pages/SchemaPage';
import SettingsPage from './pages/SettingsPage';
import { apiGet, apiPost, clearAuthToken, hasAuthToken, setAuthToken } from './api';
import type { AuditLogRow, LoginResponse, UserProfile } from './types';

const { Content, Header, Sider } = Layout;

type PageKey =
  | 'dashboard'
  | 'sources'
  | 'schema'
  | 'sync'
  | 'collectionTasks'
  | 'alerts'
  | 'rules'
  | 'notifications'
  | 'aiAgents'
  | 'reports'
  | 'settings';

const navItems: MenuProps['items'] = [
  { key: 'dashboard', icon: <DashboardOutlined />, label: '总览' },
  {
    key: 'data-source-group',
    icon: <DatabaseOutlined />,
    label: '数据源',
    children: [
      { key: 'sources', icon: <DatabaseOutlined />, label: '数据源管理' },
      { key: 'schema', icon: <TableOutlined />, label: '元数据快照' },
      { key: 'sync', icon: <SyncOutlined />, label: '外部接入' },
      { key: 'collectionTasks', icon: <CloudSyncOutlined />, label: '采集任务' },
    ],
  },
  { key: 'alerts', icon: <BellOutlined />, label: '告警中心' },
  { key: 'rules', icon: <AuditOutlined />, label: '规则中心' },
  { key: 'notifications', icon: <NotificationOutlined />, label: '通知中心' },
  { key: 'aiAgents', icon: <RobotOutlined />, label: 'AI 运营建议' },
  { key: 'reports', icon: <FileExcelOutlined />, label: '报表交付' },
  { key: 'settings', icon: <SettingOutlined />, label: '设置' },
];

const userMenuItems: MenuProps['items'] = [
  { key: 'profile', label: '账号信息' },
  { key: 'audit', label: '操作审计' },
  { type: 'divider' },
  { key: 'logout', label: '退出登录' },
];

function renderPage(page: PageKey, onNavigate: (page: PageKey) => void) {
  switch (page) {
    case 'sources':
      return <DataSourcesPage />;
    case 'schema':
      return <SchemaPage />;
    case 'sync':
      return <AlertSyncPage />;
    case 'collectionTasks':
      return <CollectionTasksPage />;
    case 'alerts':
      return <AlertsPage />;
    case 'rules':
      return <RulesPage />;
    case 'notifications':
      return <NotificationsPage />;
    case 'reports':
      return <ReportsPage />;
    case 'aiAgents':
      return <AiAgentPage />;
    case 'settings':
      return <SettingsPage />;
    default:
      return <DashboardPage onNavigate={onNavigate} />;
  }
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

function actionLabel(value?: string) {
  return {
    '创建报表任务': '创建报表任务',
    '执行数据库心跳检测': '执行数据库心跳检测',
    '采集外部告警': '采集外部告警',
    '推送告警通知': '推送告警通知',
    create_report_job: '创建报表任务',
  }[value || ''] ?? (value || '-');
}

function targetLabel(type?: string, id?: string) {
  const label = {
    report_job: '报表任务',
    data_source: '数据源',
    rule: '预警规则',
    alert: '告警',
    notification_channel: '通知通道',
  }[type || ''] ?? '对象';
  if (!id) {
    return label;
  }
  return /^\d+$/.test(id) ? `${label}记录` : `${label}：${id}`;
}

function parseAuditDetail(value: AuditLogRow['detail_json']) {
  if (!value) {
    return {};
  }
  if (typeof value === 'object') {
    return value;
  }

  let text = value.trim();
  for (let index = 0; index < 4; index += 1) {
    if (!text) {
      return {};
    }
    try {
      const parsed = JSON.parse(text);
      if (typeof parsed === 'string') {
        text = parsed;
        continue;
      }
      if (parsed && typeof parsed === 'object') {
        return parsed as Record<string, unknown>;
      }
    } catch {
      // Keep trying with a base64 decode below.
    }

    if (/^[A-Za-z0-9+/=]+$/.test(text) && text.length % 4 === 0) {
      try {
        text = atob(text);
        continue;
      } catch {
        break;
      }
    }
    break;
  }
  return { note: text };
}

function auditDetailView(value: AuditLogRow['detail_json']) {
  const detail = parseAuditDetail(value);
  const periodLabel = {
    today: '今日',
    last_7_days: '近 7 天',
    last_30_days: '近 30 天',
  }[String(detail.period || '')];
  const scopeLabel = {
    all: '全部数据源',
    external: '外部接入数据',
    open_alerts: '仅开放告警',
  }[String(detail.scope || '')];

  const items = [
    periodLabel && ['统计周期', periodLabel],
    scopeLabel && ['数据范围', scopeLabel],
    typeof detail.includeRaw === 'boolean' && ['明细数据', detail.includeRaw ? '包含' : '不包含'],
    detail.result && ['结果', String(detail.result) === 'success' ? '成功' : String(detail.result)],
    detail.latency && ['耗时', String(detail.latency)],
    detail.source && ['来源', String(detail.source)],
    detail.channel && ['通道', String(detail.channel)],
    typeof detail.created === 'number' && ['新增', `${detail.created} 条`],
    typeof detail.updated === 'number' && ['更新', `${detail.updated} 条`],
    Array.isArray(detail.recipients) && detail.recipients.length > 0 && ['发送对象', detail.recipients.join('、')],
    detail.note && ['备注', String(detail.note)],
  ].filter(Boolean) as string[][];

  if (items.length === 0) {
    return <span className="audit-detail-muted">已记录操作参数</span>;
  }

  return (
    <div className="audit-detail-list">
      {items.map(([label, text]) => (
        <span key={label}>
          <b>{label}</b>
          {text}
        </span>
      ))}
    </div>
  );
}

type LoginFormValues = {
  username: string;
  password: string;
};

function LoginPage({ onLogin }: { onLogin: (profile: UserProfile) => void }) {
  const [form] = Form.useForm<LoginFormValues>();
  const [loading, setLoading] = useState(false);

  async function handleLogin(values: LoginFormValues) {
    setLoading(true);
    try {
      const session = await apiPost<LoginResponse>('/api/auth/login', values);
      setAuthToken(session.accessToken);
      const userProfile = await apiGet<UserProfile>('/api/auth/me').catch(() => ({
        username: values.username,
        displayName: '平台管理员',
        roles: ['ADMIN'],
      }));
      onLogin(userProfile);
      message.success('登录成功');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '登录失败，请检查账号密码');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-shell">
      <div className="login-visual">
        <div className="login-brand">
          <div className="brand-mark login-brand-mark">
            <SafetyCertificateOutlined />
          </div>
          <div>
            <Typography.Title level={2}>数据安全预警分析平台</Typography.Title>
            <span>本地化部署 · 告警采集 · 风险分析 · 通知闭环</span>
          </div>
        </div>
        <img src={loginHero} alt="" />
      </div>

      <Card className="login-card">
        <div className="login-card-title">
          <Typography.Title level={3}>登录工作台</Typography.Title>
          <span>使用管理员账号进入安全运营工作台</span>
        </div>
        <Form<LoginFormValues>
          form={form}
          layout="vertical"
          initialValues={{ username: 'admin' }}
          onFinish={handleLogin}
          requiredMark={false}
        >
          <Form.Item name="username" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
            <Input size="large" prefix={<UserOutlined />} placeholder="请输入账号" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password
              size="large"
              prefix={<LockOutlined />}
              placeholder="请输入密码"
              autoComplete="current-password"
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block icon={<LoginOutlined />} loading={loading}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}

function App() {
  const [authenticated, setAuthenticated] = useState(() => hasAuthToken());
  const [activePage, setActivePage] = useState<PageKey>('dashboard');
  const [profileOpen, setProfileOpen] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const [profileLoading, setProfileLoading] = useState(false);
  const [auditLoading, setAuditLoading] = useState(false);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [auditRows, setAuditRows] = useState<AuditLogRow[]>([]);
  const selectedKeys = useMemo(() => [activePage], [activePage]);

  async function openProfile() {
    setProfileOpen(true);
    setProfileLoading(true);
    try {
      setProfile(await apiGet<UserProfile>('/api/auth/me'));
    } catch {
      setProfile({
        username: 'admin',
        displayName: '平台管理员',
        roles: ['ADMIN'],
      });
      message.warning('账号信息接口暂不可用，已展示本地默认账号。');
    } finally {
      setProfileLoading(false);
    }
  }

  async function openAuditLogs() {
    setAuditOpen(true);
    setAuditLoading(true);
    try {
      setAuditRows(await apiGet<AuditLogRow[]>('/api/core/audit-logs?limit=80'));
    } catch {
      setAuditRows([]);
      message.warning('操作审计暂不可用，请确认后端服务状态。');
    } finally {
      setAuditLoading(false);
    }
  }

  function handleUserMenuClick({ key }: { key: string }) {
    if (key === 'profile') {
      void openProfile();
      return;
    }
    if (key === 'audit') {
      void openAuditLogs();
      return;
    }
    if (key === 'logout') {
      clearAuthToken();
      setAuthenticated(false);
      setProfile(null);
      message.success('已退出登录');
    }
  }

  const auditColumns: ColumnsType<AuditLogRow> = [
    {
      title: '时间',
      dataIndex: 'created_at',
      width: 140,
      render: formatTime,
    },
    {
      title: '操作者',
      dataIndex: 'actor',
      width: 110,
      render: (value) => value || '-',
    },
    {
      title: '动作',
      dataIndex: 'action',
      width: 220,
      render: (value: string, row) => (
        <div className="audit-action-cell">
          <strong>{actionLabel(value)}</strong>
          <span>{targetLabel(row.target_type, row.target_id)}</span>
        </div>
      ),
    },
    {
      title: '详情',
      dataIndex: 'detail_json',
      render: auditDetailView,
    },
  ];

  if (!authenticated) {
    return (
      <LoginPage
        onLogin={(nextProfile) => {
          setProfile(nextProfile);
          setAuthenticated(true);
        }}
      />
    );
  }

  return (
    <div className="windows-app-frame">
      <Layout className="app-shell">
        <Sider className="edsp-sider" width={230} breakpoint="lg" collapsedWidth={76}>
          <div className="brand">
            <div className="brand-mark">
              <SafetyCertificateOutlined />
            </div>
            <div>
              <Typography.Title level={4}>数据安全预警分析平台</Typography.Title>
              <span>安全运营中心</span>
            </div>
          </div>

          <Menu
            className="sider-menu"
            mode="inline"
            defaultOpenKeys={['data-source-group']}
            selectedKeys={selectedKeys}
            items={navItems}
            onClick={({ key }) => setActivePage(key as PageKey)}
          />
        </Sider>

        <Layout className="workspace-shell">
          <Header className="topbar">
            <div className="topbar-context">
              <SafetyCertificateOutlined />
              <span>安全运营中心</span>
            </div>

            <Space size={12} className="topbar-actions">
              <Segmented options={['今日', '7天', '30天']} defaultValue="今日" />
              <div className="top-search">
                <ApiOutlined />
                <span>搜索</span>
              </div>
              <Tag className="status-chip success" icon={<CheckCircleFilled />}>
                平台正常
              </Tag>
              <Tag className="status-chip warning" icon={<WarningFilled />}>
                外部接入待配置
              </Tag>
              <Badge dot>
                <Button type="text" shape="circle" icon={<BellOutlined />} />
              </Badge>
              <Dropdown menu={{ items: userMenuItems, onClick: handleUserMenuClick }} trigger={['click']}>
                <Button type="text" className="user-trigger">
                  <Space size={8}>
                    <Avatar size={30} icon={<UserOutlined />} />
                    <span>{profile?.username || 'admin'}</span>
                    <DownOutlined />
                  </Space>
                </Button>
              </Dropdown>
            </Space>
          </Header>

          <Content className="page-content">{renderPage(activePage, setActivePage)}</Content>
        </Layout>
      </Layout>

      <Modal
        title="账号信息"
        open={profileOpen}
        onCancel={() => setProfileOpen(false)}
        footer={null}
        loading={profileLoading}
      >
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label="账号">{profile?.username || 'admin'}</Descriptions.Item>
          <Descriptions.Item label="显示名称">{profile?.displayName || '-'}</Descriptions.Item>
          <Descriptions.Item label="角色">
            <Space wrap>
              {(profile?.roles || ['ADMIN']).map((role) => (
                <Tag key={role} color="blue">
                  {role === 'ADMIN' ? '管理员' : role}
                </Tag>
              ))}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="登录模式">本地账号登录</Descriptions.Item>
        </Descriptions>
      </Modal>

      <Drawer
        title="操作审计"
        width={900}
        open={auditOpen}
        onClose={() => setAuditOpen(false)}
        extra={
          <Button icon={<SyncOutlined />} loading={auditLoading} onClick={openAuditLogs}>
            刷新
          </Button>
        }
      >
        <Table<AuditLogRow>
          rowKey="id"
          size="small"
          loading={auditLoading}
          dataSource={auditRows}
          columns={auditColumns}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: '暂无操作审计记录。关键操作会持续写入这里。' }}
        />
      </Drawer>
    </div>
  );
}

export default App;
