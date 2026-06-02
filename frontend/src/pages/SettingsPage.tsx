import { Alert, Button, Card, Descriptions, Drawer, Form, InputNumber, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../api';
import BusinessStatusTag from '../components/BusinessStatusTag';
import type { AiAgentProviderConfig, AiAgentProviderTestResult } from '../types';
import {
  formatBusinessTime,
  getProviderConfigStatus,
  getProviderEnabledStatus,
  getProviderTestStatus,
} from '../utils/businessDisplay';

interface SettingsPageProps {
  onNavigate?: (page: 'aiAgents') => void;
}

export default function SettingsPage({ onNavigate }: SettingsPageProps) {
  const [form] = Form.useForm();
  const [providerConfigs, setProviderConfigs] = useState<AiAgentProviderConfig[]>([]);
  const [providerLoading, setProviderLoading] = useState(false);
  const [testingProviderKey, setTestingProviderKey] = useState<string>();
  const [activeGuide, setActiveGuide] = useState<AiAgentProviderConfig>();

  async function saveSettings() {
    await form.validateFields();
    message.info('当前版本仅展示推荐运行参数，暂未写入系统配置。');
  }

  async function loadProviderConfigs() {
    setProviderLoading(true);
    try {
      setProviderConfigs(await apiGet<AiAgentProviderConfig[]>('/api/core/ai-agent-provider-configs'));
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setProviderLoading(false);
    }
  }

  async function testProvider(row: AiAgentProviderConfig) {
    setTestingProviderKey(row.providerKey);
    try {
      const result = await apiPost<AiAgentProviderTestResult>(
        `/api/core/ai-agent-provider-configs/${encodeURIComponent(row.providerKey)}/test`,
        {}
      );
      setProviderConfigs((rows) => rows.map((item) => (
        item.providerKey === row.providerKey
          ? { ...item, lastTestStatus: result.status, lastTestMessage: result.message }
          : item
      )));
      if (result.status === 'passed') {
        message.success(`${result.displayName}连接测试通过`);
      } else {
        message.warning(result.message);
      }
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setTestingProviderKey(undefined);
    }
  }

  useEffect(() => {
    void loadProviderConfigs();
  }, []);

  const providerColumns: ColumnsType<AiAgentProviderConfig> = [
    {
      title: '模型类型',
      dataIndex: 'displayName',
      render: (value, row) => (
        <Space direction="vertical" size={0}>
          <span>{value}</span>
          <Typography.Text type="secondary">{row.providerType === 'fallback' ? '内置模板' : 'OpenAI 兼容接口'}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '配置状态',
      render: (_, row) => (
        <Space wrap>
          {row.providerType === 'fallback' ? <Tag color="success">不需要配置</Tag> : null}
          {row.providerType !== 'fallback' ? <BusinessStatusTag status={getProviderConfigStatus(row.baseUrlConfigured)} /> : null}
          {row.providerType !== 'fallback' ? <Tag color={row.apiKeyConfigured ? 'success' : 'warning'}>API Key：{row.apiKeyMask}</Tag> : null}
          <Tag color={row.modelConfigured ? 'success' : 'warning'}>模型：{row.modelDisplay}</Tag>
        </Space>
      ),
    },
    {
      title: '启用状态',
      dataIndex: 'enabled',
      render: (value) => <BusinessStatusTag status={getProviderEnabledStatus(Boolean(value))} />,
    },
    {
      title: '最近测试',
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <BusinessStatusTag status={getProviderTestStatus(row.lastTestStatus)} />
          <Typography.Text type="secondary">{row.lastTestMessage || '尚未测试'}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '操作',
      width: 260,
      render: (_, row) => (
        <Space wrap>
          <Button
            size="small"
            disabled={!row.testable}
            loading={testingProviderKey === row.providerKey}
            onClick={() => void testProvider(row)}
          >
            测试连接
          </Button>
          <Button size="small" onClick={() => setActiveGuide(row)}>
            查看接入说明
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="settings-page">
      <div className="ops-heading">
        <div>
          <h3 className="ant-typography">系统设置</h3>
          <span>查看平台运行建议、采集默认值、安全策略和通知默认行为</span>
        </div>
        <Button type="primary" onClick={saveSettings}>
          预览设置
        </Button>
      </div>

      <div className="settings-grid">
        <Card className="ops-card" title="平台信息">
          <Alert
            className="form-hint"
            type="info"
            showIcon
            message="这里展示的是交付演示参数。正式系统配置保存接口开放后，才会写入后端配置。"
          />
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="系统名称">数据安全预警分析平台</Descriptions.Item>
            <Descriptions.Item label="服务形态">本地化部署的模块化服务</Descriptions.Item>
            <Descriptions.Item label="运行模式">
              <Tag color="blue">本地化部署</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="外部接入">数据库、API、Webhook、文件导入、安全平台</Descriptions.Item>
          </Descriptions>
        </Card>

        <Card className="ops-card" title="运行参数">
          <Form
            form={form}
            layout="vertical"
            initialValues={{
              heartbeatInterval: 5,
              collectInterval: 10,
              maxRows: 100,
              retentionDays: 180,
              pushMode: 'important',
              autoRetry: true,
              auditEnabled: true,
            }}
          >
            <Space className="settings-form-grid" align="start">
              <Form.Item name="heartbeatInterval" label="心跳间隔" rules={[{ required: true }]}>
                <InputNumber min={1} max={1440} addonAfter="分钟" />
              </Form.Item>
              <Form.Item name="collectInterval" label="采集间隔" rules={[{ required: true }]}>
                <InputNumber min={1} max={1440} addonAfter="分钟" />
              </Form.Item>
            </Space>
            <Space className="settings-form-grid" align="start">
              <Form.Item name="maxRows" label="单次采集上限" rules={[{ required: true }]}>
                <InputNumber min={10} max={10000} addonAfter="行" />
              </Form.Item>
              <Form.Item name="retentionDays" label="审计保留周期" rules={[{ required: true }]}>
                <InputNumber min={30} max={3650} addonAfter="天" />
              </Form.Item>
            </Space>
            <Form.Item name="pushMode" label="默认推送策略">
              <Select
                options={[
                  { value: 'all', label: '所有告警' },
                  { value: 'important', label: '中危及以上' },
                  { value: 'critical', label: '仅严重/高危' },
                  { value: 'manual', label: '人工确认后推送' },
                ]}
              />
            </Form.Item>
            <Space size={28}>
              <Form.Item name="autoRetry" label="失败自动重试" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="auditEnabled" label="启用操作审计" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Space>
          </Form>
        </Card>
      </div>

      <Card
        className="ops-card"
        title="大模型接入"
        style={{ marginTop: 16 }}
        extra={<Button onClick={() => onNavigate?.('aiAgents')}>前往 AI 运营建议</Button>}
      >
        <Alert
          className="form-hint"
          type="info"
          showIcon
          message="配置企业云模型后，AI 运营建议可使用第三方 OpenAI 兼容接口生成分析结果。API Key 只在服务端环境变量中读取，不会在页面回显。"
        />
        <Table
          rowKey="providerKey"
          loading={providerLoading}
          pagination={false}
          columns={providerColumns}
          dataSource={providerConfigs}
          locale={{ emptyText: '暂未获取到模型接入状态，请检查 AI Agent 服务是否可用。' }}
        />
      </Card>

      <Drawer
        title={`${activeGuide?.displayName || '模型'}接入说明`}
        width={560}
        open={Boolean(activeGuide)}
        onClose={() => setActiveGuide(undefined)}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="API Key 只允许写入服务端部署环境，不会在页面回显，也不会写入 AI 运行记录。"
          />
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="接入方式">
              {activeGuide?.providerType === 'fallback' ? '内置安全模板' : 'OpenAI 兼容接口'}
            </Descriptions.Item>
            <Descriptions.Item label="配置状态">
              {activeGuide?.providerType === 'fallback' ? '不需要配置' : activeGuide?.configureHint}
            </Descriptions.Item>
            <Descriptions.Item label="最近测试">
              {activeGuide ? `${getProviderTestStatus(activeGuide.lastTestStatus).label}：${activeGuide.lastTestMessage}` : '-'}
            </Descriptions.Item>
          </Descriptions>
          {activeGuide?.providerKey === 'cloud-openai-compatible' ? (
            <pre className="code-block">
{`EDSP_AI_CLOUD_OPENAI_ENABLED=true
EDSP_AI_CLOUD_OPENAI_BASE_URL=https://example.com/v1/chat/completions
EDSP_AI_CLOUD_OPENAI_API_KEY=********
EDSP_AI_CLOUD_OPENAI_MODEL=your-model-name`}
            </pre>
          ) : null}
          <Typography.Paragraph type="secondary">
            示例中的 API Key 必须保持为 ********。当前阶段只展示 readiness 状态和连接测试结果，不支持在页面保存密钥。
          </Typography.Paragraph>
        </Space>
      </Drawer>
    </div>
  );
}
