import { Button, Card, Descriptions, Form, InputNumber, Select, Space, Switch, Tag, message } from 'antd';

export default function SettingsPage() {
  const [form] = Form.useForm();

  async function saveSettings() {
    await form.validateFields();
    message.success('系统设置已保存到当前工作台');
  }

  return (
    <div className="settings-page">
      <div className="ops-heading">
        <div>
          <h3 className="ant-typography">系统设置</h3>
          <span>配置平台运行、采集默认值、安全策略和通知默认行为</span>
        </div>
        <Button type="primary" onClick={saveSettings}>
          保存设置
        </Button>
      </div>

      <div className="settings-grid">
        <Card className="ops-card" title="平台信息">
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="系统名称">数据安全预警分析平台</Descriptions.Item>
            <Descriptions.Item label="后端架构">Java 21 / Spring Boot 微服务</Descriptions.Item>
            <Descriptions.Item label="运行模式">
              <Tag color="blue">本地化部署</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="外部接入">数据库、API、Webhook 适配器扩展</Descriptions.Item>
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
    </div>
  );
}
