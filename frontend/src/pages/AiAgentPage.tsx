import { ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Form, Row, Select, Space, Table, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../api';
import AdvancedDetailsCollapse from '../components/AdvancedDetailsCollapse';
import BusinessStatusTag from '../components/BusinessStatusTag';
import NextStepHint from '../components/NextStepHint';
import type { AiAgentProviderConfig, AiAgentRecentRun, AiAgentRunResult } from '../types';
import {
  formatBusinessTime,
  getAiRunStatus,
  getPeriodLabel,
  getProviderLabel,
  getSourceLabel,
  getThemeLabel,
} from '../utils/businessDisplay';

const periods = [
  { value: 'last_24h', label: '最近 24 小时' },
  { value: 'last_7_days', label: getPeriodLabel('last_7_days') },
  { value: 'last_30_days', label: getPeriodLabel('last_30_days') },
];

const themes = [
  { value: 'security_overview', label: getThemeLabel('security_overview') },
  { value: 'high_risk_alerts', label: getThemeLabel('high_risk_alerts') },
  { value: 'rule_effectiveness', label: getThemeLabel('rule_effectiveness') },
  { value: 'sync_pipeline_health', label: getThemeLabel('sync_pipeline_health') },
  { value: 'notification_readiness', label: getThemeLabel('notification_readiness') },
];

function providerReady(provider: AiAgentProviderConfig) {
  if (provider.providerType === 'fallback') {
    return true;
  }
  if (!provider.enabled || !provider.baseUrlConfigured || !provider.modelConfigured) {
    return false;
  }
  if (provider.providerKey === 'cloud-openai-compatible') {
    return provider.apiKeyConfigured;
  }
  return true;
}

export default function AiAgentPage() {
  const [providers, setProviders] = useState<AiAgentProviderConfig[]>([]);
  const [recent, setRecent] = useState<AiAgentRecentRun[]>([]);
  const [result, setResult] = useState<AiAgentRunResult>();
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    const [providerRows, recentRows] = await Promise.all([
      apiGet<AiAgentProviderConfig[]>('/api/core/ai-agent-provider-configs'),
      apiGet<AiAgentRecentRun[]>('/api/core/ai-agents/runs/recent?limit=10'),
    ]);
    setProviders(providerRows);
    setRecent(recentRows);
    const currentProviderKey = form.getFieldValue('providerKey');
    const currentProvider = providerRows.find((provider) => provider.providerKey === currentProviderKey);
    if (!currentProviderKey || (currentProvider && !providerReady(currentProvider))) {
      form.setFieldValue(
        'providerKey',
        providerRows.find((provider) => providerReady(provider))?.providerKey ?? 'fallback-template'
      );
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
          <Typography.Title level={3}>AI 运营建议</Typography.Title>
          <Typography.Text type="secondary">
            基于安全告警、规则决策和同步链路生成只读运营建议，不会修改告警状态，也不会发送通知。
          </Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>

      <Alert type="info" showIcon message="智能体只读取聚合指标，不会修改告警状态，也不会发送通知。" />
      {providers.some((provider) => provider.providerKey === 'cloud-openai-compatible' && !providerReady(provider)) ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 12 }}
          message="企业云模型尚未完成接入"
          description="请先到系统设置的大模型接入区域完成部署环境变量配置并测试连接。安全模板生成仍可使用。"
        />
      ) : null}

      <Card className="ops-card ai-agent-run-panel">
        <Form form={form} layout="inline" initialValues={{ period: 'last_7_days', theme: 'security_overview' }} onFinish={run}>
          <Form.Item name="providerKey" label="分析模型" rules={[{ required: true }]}>
            <Select style={{ width: 230 }} options={providers.map((provider) => ({
              value: provider.providerKey,
              label: providerReady(provider) ? provider.displayName : `${provider.displayName}（请先完成接入）`,
              disabled: !providerReady(provider),
              title: provider.configureHint,
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
            <BusinessStatusTag status={getAiRunStatus(result.status)} />
            <BusinessStatusTag status={{ label: getSourceLabel(result.source), color: result.source === 'fallback-template' ? 'warning' : 'success' }} />
          </Space>
          {result.source === 'fallback-template' || result.status === 'warning' ? (
            <NextStepHint
              type="warning"
              message="本次建议已降级生成"
        description="当前使用安全模板生成，模型不可用或未配置。内容仍基于系统聚合指标生成，不包含敏感明细数据；可以继续查看建议，也可以联系管理员配置模型。"
            />
          ) : null}
          <Row gutter={[12, 12]}>
            {result.sections.map((section) => (
              <Col xs={24} md={12} xl={8} key={section.title}>
                <Card className="ops-card" title={section.title}>{section.content}</Card>
              </Col>
            ))}
          </Row>
          <AdvancedDetailsCollapse
            items={[
              { label: 'providerKey', value: result.providerKey, code: true },
              { label: 'source', value: result.source, code: true },
            { label: 'status', value: result.status, code: true },
              { label: 'agentKey', value: result.agentKey, code: true },
              { label: 'period', value: result.period, code: true },
              { label: 'theme', value: result.theme, code: true },
              { label: 'warnings', value: result.warnings, code: true },
            ]}
          />
        </section>
      )}

      <Card className="ops-card" title="最近运行" style={{ marginTop: 16 }}>
        <Table rowKey="id" size="small" pagination={false} dataSource={recent} columns={[
          { title: '时间', dataIndex: 'started_at', render: formatBusinessTime },
          { title: '分析主题', dataIndex: 'theme', render: getThemeLabel },
          { title: '时间范围', dataIndex: 'period', render: getPeriodLabel },
          { title: '分析模型', dataIndex: 'provider_key', render: (value) => getProviderLabel(value) },
          { title: '生成方式', dataIndex: 'source', render: getSourceLabel },
          { title: '状态', dataIndex: 'status', render: (value) => <BusinessStatusTag status={getAiRunStatus(value)} /> },
        ]}
        expandable={{
          expandedRowRender: (row) => (
            <AdvancedDetailsCollapse
              title="本次运行技术详情"
              items={[
                { label: 'runId', value: row.id, code: true },
                { label: 'providerKey', value: row.provider_key, code: true },
                { label: 'source', value: row.source, code: true },
                { label: 'status', value: row.status, code: true },
                { label: 'period', value: row.period, code: true },
                { label: 'theme', value: row.theme, code: true },
              ]}
            />
          ),
          rowExpandable: () => true,
        }}
        locale={{ emptyText: '暂无最近运行。请点击“运行分析”生成第一份 AI 运营建议。' }}
        />
      </Card>
    </div>
  );
}

