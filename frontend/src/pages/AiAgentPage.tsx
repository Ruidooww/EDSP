import { ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Form, Row, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../api';
import type { AiAgentProvider, AiAgentRecentRun, AiAgentRunResult } from '../types';

const periods = [
  { value: 'last_24h', label: '最近 24 小时' },
  { value: 'last_7_days', label: '最近 7 天' },
  { value: 'last_30_days', label: '最近 30 天' },
];

const themes = [
  { value: 'security_overview', label: '安全态势概览' },
  { value: 'high_risk_alerts', label: '高危告警' },
  { value: 'rule_effectiveness', label: '规则有效性' },
  { value: 'sync_pipeline_health', label: '同步链路健康' },
  { value: 'notification_readiness', label: '通知准备度' },
];

export default function AiAgentPage() {
  const [providers, setProviders] = useState<AiAgentProvider[]>([]);
  const [recent, setRecent] = useState<AiAgentRecentRun[]>([]);
  const [result, setResult] = useState<AiAgentRunResult>();
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    const [providerRows, recentRows] = await Promise.all([
      apiGet<AiAgentProvider[]>('/api/core/ai-agents/providers'),
      apiGet<AiAgentRecentRun[]>('/api/core/ai-agents/runs/recent?limit=10'),
    ]);
    setProviders(providerRows);
    setRecent(recentRows);
    if (!form.getFieldValue('providerKey')) {
      form.setFieldValue('providerKey', providerRows.find((provider) => provider.enabled)?.key ?? 'fallback-template');
    }
  };

  useEffect(() => {
    void load().catch((error: Error) => message.error(error.message));
  }, []);

  const run = async (values: Record<string, string>) => {
    setLoading(true);
    try {
      setResult(await apiPost<AiAgentRunResult>('/api/core/ai-agents/runs', {
        agentKey: 'security-insight-agent',
        ...values,
      }));
      await load();
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="ai-agent-page">
      <div className="ops-heading">
        <div>
          <Typography.Title level={3}>AI 智能体分析</Typography.Title>
          <Typography.Text type="secondary">基于安全聚合指标生成只读运营建议</Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>

      <Alert type="info" showIcon message="智能体只读取聚合指标，不会修改告警状态，也不会发送通知。" />

      <Card className="ops-card ai-agent-run-panel">
        <Form form={form} layout="inline" initialValues={{ period: 'last_7_days', theme: 'security_overview' }} onFinish={run}>
          <Form.Item name="providerKey" label="Provider" rules={[{ required: true }]}>
            <Select style={{ width: 230 }} options={providers.map((provider) => ({
              value: provider.key,
              label: provider.enabled ? provider.key : `${provider.key}（未配置）`,
              disabled: !provider.enabled,
            }))} />
          </Form.Item>
          <Form.Item name="period" label="时间范围"><Select style={{ width: 150 }} options={periods} /></Form.Item>
          <Form.Item name="theme" label="分析主题"><Select style={{ width: 170 }} options={themes} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} icon={<RobotOutlined />}>运行分析</Button>
        </Form>
      </Card>

      {result && (
        <section>
          <Space style={{ marginBottom: 12 }}>
            <Tag color={result.status === 'passed' ? 'success' : 'warning'}>{result.status}</Tag>
            <Tag>{result.source}</Tag>
          </Space>
          <Row gutter={[12, 12]}>
            {result.sections.map((section) => (
              <Col xs={24} md={12} xl={8} key={section.title}>
                <Card className="ops-card" title={section.title}>{section.content}</Card>
              </Col>
            ))}
          </Row>
        </section>
      )}

      <Card className="ops-card" title="最近运行" style={{ marginTop: 16 }}>
        <Table rowKey="id" size="small" pagination={false} dataSource={recent} columns={[
          { title: '时间', dataIndex: 'started_at' },
          { title: '主题', dataIndex: 'theme' },
          { title: '范围', dataIndex: 'period' },
          { title: 'Provider', dataIndex: 'provider_key' },
          { title: '来源', dataIndex: 'source' },
          { title: '状态', dataIndex: 'status' },
        ]} />
      </Card>
    </div>
  );
}

