import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, message } from 'antd';
import { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../api';
import type { SchemaTableRow } from '../types';

export default function SchemaPage() {
  const [rows, setRows] = useState<SchemaTableRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  async function load() {
    setLoading(true);
    try {
      setRows(await apiGet<SchemaTableRow[]>('/api/core/schema/tables'));
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function submit() {
    const values = await form.validateFields();
    await apiPost('/api/core/schema/tables', values);
    message.success('Schema 表已创建');
    setOpen(false);
    form.resetFields();
    await load();
  }

  return (
    <Card
      title="Schema 映射"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新增表</Button>
        </Space>
      }
    >
      <Table<SchemaTableRow>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={[
          { title: '数据源', dataIndex: 'data_source_name' },
          { title: '表/文件', dataIndex: 'table_name' },
          { title: '分类', dataIndex: 'category' },
          {
            title: '确认状态',
            dataIndex: 'confirmation_status',
            render: (value) => <Tag color={value === 'confirmed' ? 'success' : 'warning'}>{value}</Tag>,
          },
        ]}
        locale={{ emptyText: '暂无 Schema，可在真实数据接入前手动维护字段映射。' }}
      />

      <Modal title="新增 Schema 表" open={open} onOk={submit} onCancel={() => setOpen(false)} okText="保存">
        <Form layout="vertical" form={form} initialValues={{ confirmationStatus: 'pending' }}>
          <Form.Item name="dataSourceId" label="数据源 ID" rules={[{ required: true, message: '请输入数据源 ID' }]}>
            <InputNumber min={1} className="full-width" />
          </Form.Item>
          <Form.Item name="tableName" label="表/文件名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：DOC_LOG、events.csv" />
          </Form.Item>
          <Form.Item name="category" label="业务分类">
            <Input placeholder="例如：file_operation、mail、usb" />
          </Form.Item>
          <Form.Item name="confirmationStatus" label="确认状态">
            <Select
              options={[
                { value: 'pending', label: '待确认' },
                { value: 'confirmed', label: '已确认' },
                { value: 'ignored', label: '已忽略' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
