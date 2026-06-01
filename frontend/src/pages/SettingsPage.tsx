import { Alert, Button, Card, Descriptions, Form, InputNumber, Select, Space, Switch, Tag, message } from 'antd';

export default function SettingsPage() {
  const [form] = Form.useForm();

  async function saveSettings() {
    await form.validateFields();
    message.info('当前版本仅展示推荐运行参数，暂未写入系统配置。');
  }

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
    </div>
  );
}
