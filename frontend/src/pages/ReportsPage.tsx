import {
  BarChartOutlined,
  CloudDownloadOutlined,
  FileDoneOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
} from '@ant-design/icons';
import { Button, Card, Checkbox, Form, Input, Modal, Progress, Select, Space, Table, Tag, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost } from '../api';
import AdvancedDetailsCollapse from '../components/AdvancedDetailsCollapse';
import BusinessStatusTag from '../components/BusinessStatusTag';
import NextStepHint from '../components/NextStepHint';
import type { OverviewData, ReportJobRow } from '../types';
import { formatBusinessTime, getPeriodLabel, getReportStatus } from '../utils/businessDisplay';

interface ReportFormValues {
  title: string;
  reportType: string;
  period: string;
  scope: string;
  includeRaw: boolean;
  recipients?: string;
}

const reportTypes = [
  { value: 'risk_summary', label: '风险汇总' },
  { value: 'incident', label: '告警处置' },
  { value: 'audit', label: '审计材料' },
  { value: 'integration', label: '外部接入' },
  { value: 'delivery', label: '通知投递' },
];

const reportTemplates = [
  {
    type: 'risk_summary',
    title: '每日风险汇总',
    description: '汇总风险等级、开放告警、数据源健康和规则启用情况。',
    icon: <BarChartOutlined />,
  },
  {
    type: 'incident',
    title: '告警处置跟踪',
    description: '面向安全运营人员，追踪未处理、处理中、已恢复告警。',
    icon: <SafetyCertificateOutlined />,
  },
  {
    type: 'integration',
    title: '外部接入巡检',
    description: '记录外部系统心跳、采集状态、标准化入库和推送链路。',
    icon: <ScheduleOutlined />,
  },
  {
    type: 'audit',
    title: '合规审计材料',
    description: '输出关键操作、策略配置、告警处理和导出留痕。',
    icon: <FileDoneOutlined />,
  },
];

function reportTypeLabel(value: string) {
  return reportTypes.find((item) => item.value === value)?.label ?? value;
}

function statusTag(value: string) {
  return <BusinessStatusTag status={getReportStatus(value)} />;
}

function formatTime(value?: string | number) {
  return formatBusinessTime(value);
}

export default function ReportsPage() {
  const [rows, setRows] = useState<ReportJobRow[]>([]);
  const [overview, setOverview] = useState<OverviewData | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<ReportFormValues>();

  async function load() {
    setLoading(true);
    try {
      const [jobs, overviewData] = await Promise.all([
        apiGet<ReportJobRow[]>('/api/reports/jobs'),
        apiGet<OverviewData>('/api/core/overview'),
      ]);
      setRows(jobs);
      setOverview(overviewData);
    } catch {
      setRows([]);
      setOverview(null);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function openCreate(type = 'risk_summary', title?: string) {
    form.setFieldsValue({
      title: title ?? `${reportTypeLabel(type)}报表`,
      reportType: type,
      period: 'today',
      scope: 'all',
      includeRaw: false,
      recipients: '',
    });
    setOpen(true);
  }

  async function submit() {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await apiPost('/api/reports/jobs', {
        title: values.title,
        reportType: values.reportType,
        paramsJson: JSON.stringify({
          period: values.period,
          scope: values.scope,
          includeRaw: values.includeRaw,
          recipients: values.recipients
            ?.split(/[,\n]/)
            .map((item) => item.trim())
            .filter(Boolean),
        }),
      });
      message.success('报表任务已生成，可在列表中下载');
      setOpen(false);
      form.resetFields();
      await load();
    } finally {
      setSaving(false);
    }
  }

  function downloadReport(row: ReportJobRow) {
    if (!row.file_path) {
      message.info('该报表尚未生成文件，请稍后刷新或重新生成');
      return;
    }
    window.open(row.file_path, '_blank');
  }

  const completionRate = useMemo(() => {
    const total = overview?.reports.total ?? rows.length;
    if (!total) {
      return 0;
    }
    return Math.round(((overview?.reports.completed ?? rows.filter((row) => row.status === 'completed').length) / total) * 100);
  }, [overview, rows]);

  const columns: ColumnsType<ReportJobRow> = [
    {
      title: '报表名称',
      dataIndex: 'title',
      render: (value: string, row) => (
        <div>
          <strong>{value}</strong>
          <span className="table-subtext">{reportTypeLabel(row.report_type)}</span>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: statusTag,
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      width: 150,
      render: formatTime,
    },
    {
      title: '文件',
      dataIndex: 'file_path',
      render: (value, row) => value ? (
        <Space direction="vertical" size={4}>
          <Tag color="success">文件已准备</Tag>
          <AdvancedDetailsCollapse
            title="文件技术详情"
            items={[
              { label: 'file_path', value: row.file_path, code: true },
              { label: 'reportType', value: row.report_type, code: true },
              { label: 'status', value: row.status, code: true },
            ]}
          />
        </Space>
      ) : <Tag>未生成</Tag>,
    },
    {
      title: '操作',
      align: 'right',
      width: 110,
      render: (_, row) => (
        <Button size="small" icon={<CloudDownloadOutlined />} disabled={!row.file_path} onClick={() => downloadReport(row)}>
          下载
        </Button>
      ),
    },
  ];

  return (
    <div className="reports-page">
      <div className="ops-heading">
        <div>
          <h3 className="ant-typography">报表交付</h3>
          <span>下载安全运营日报、告警处置跟踪、通知投递统计和合规审计材料。</span>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            刷新
          </Button>
          <Button icon={<CloudDownloadOutlined />} href="/api/reports/exports/empty-template">
            下载空模板
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate()}>
            生成演示报告
          </Button>
        </Space>
      </div>

      <div className="report-summary-grid">
        <Card className="ops-card report-summary-tile">
          <span>报表任务</span>
          <strong>{overview?.reports.total ?? rows.length}</strong>
          <small>已生成 {overview?.reports.completed ?? rows.filter((row) => row.status === 'completed').length}</small>
        </Card>
        <Card className="ops-card report-summary-tile">
          <span>开放告警</span>
          <strong>{overview?.alerts.open ?? 0}</strong>
          <small>今日新增 {overview?.alerts.today ?? 0}</small>
        </Card>
        <Card className="ops-card report-summary-tile">
          <span>数据源健康度</span>
          <strong>{overview?.dataSources.healthRate ?? 0}%</strong>
          <small>健康 {overview?.dataSources.healthy ?? 0} / {overview?.dataSources.total ?? 0}</small>
        </Card>
        <Card className="ops-card report-summary-tile">
          <span>生成完成率</span>
          <Progress type="circle" size={72} percent={completionRate} />
        </Card>
      </div>

      <Card className="ops-card" title="常用报表模板">
        <div className="report-template-grid">
          {reportTemplates.map((template) => (
            <div className="report-template-tile" key={template.type}>
              <span className="report-template-icon">{template.icon}</span>
              <div>
                <strong>{template.title}</strong>
                <p>{template.description}</p>
              </div>
              <Button size="small" onClick={() => openCreate(template.type, template.title)}>
                使用模板
              </Button>
            </div>
          ))}
        </div>
      </Card>

      <Card className="ops-card" title="报表任务">
        <NextStepHint description="没有可下载文件时，请先从常用模板生成报表；生成失败时可重新生成后再下载交付。" />
        <Table<ReportJobRow>
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 920 }}
          locale={{ emptyText: '暂无报表任务。可以先从上方模板创建第一份交付报表。' }}
        />
      </Card>

      <Modal
        title="新建报表任务"
        open={open}
        onOk={submit}
        onCancel={() => setOpen(false)}
        okText="生成"
        confirmLoading={saving}
        destroyOnClose
      >
        <Form
          layout="vertical"
          form={form}
          initialValues={{
            reportType: 'risk_summary',
            period: 'today',
            scope: 'all',
            includeRaw: false,
          }}
        >
          <Form.Item name="title" label="报表名称" rules={[{ required: true, message: '请输入报表名称' }]}>
            <Input placeholder="例如：每日风险汇总" />
          </Form.Item>
          <Form.Item name="reportType" label="报表类型" rules={[{ required: true, message: '请选择报表类型' }]}>
            <Select options={reportTypes} />
          </Form.Item>
          <Space className="form-grid-2" align="start">
            <Form.Item name="period" label="统计周期" rules={[{ required: true, message: '请选择统计周期' }]}>
              <Select
                options={[
                  { value: 'today', label: '今日' },
                  { value: 'last_7_days', label: getPeriodLabel('last_7_days') },
                  { value: 'last_30_days', label: getPeriodLabel('last_30_days') },
                  { value: 'custom', label: '自定义周期' },
                ]}
              />
            </Form.Item>
            <Form.Item name="scope" label="数据范围">
              <Select
                options={[
                  { value: 'all', label: '全部数据源' },
                  { value: 'external', label: '外部接入数据' },
                  { value: 'open_alerts', label: '仅开放告警' },
                ]}
              />
            </Form.Item>
          </Space>
          <Form.Item name="includeRaw" valuePropName="checked">
            <Checkbox>包含明细数据 Sheet（高级交付）</Checkbox>
          </Form.Item>
          <Form.Item name="recipients" label="发送对象">
            <Input.TextArea rows={3} placeholder="可填写接收人或邮箱，多个用换行或逗号分隔" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
