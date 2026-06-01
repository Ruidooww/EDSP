import { Descriptions, Drawer, Space, Typography } from 'antd';
import type { IngestionPlanShadowRunReport, IngestionPlanShadowRunRow } from '../../../types';
import {
  formatUnknownValue,
  renderTextTags,
  shadowValidationResultTag,
  STANDARD_FIELD_LABELS,
} from '../utils/ingestionPlanLabels';
import AdvancedDetailsCollapse from '../../../components/AdvancedDetailsCollapse';
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';
import IngestionPlanDetailSection from './IngestionPlanDetailSection';

interface IngestionPlanShadowRunReportDrawerProps {
  plan: NormalizedIngestionPlan | null;
  run: IngestionPlanShadowRunRow | null;
  formatTime: (value?: string | number) => string;
  onClose: () => void;
}

function numberValue(value: unknown) {
  return typeof value === 'number' ? value : '-';
}

function renderRecord(record?: Record<string, unknown>) {
  if (!record || !Object.keys(record).length) {
    return '-';
  }
  return (
    <Descriptions bordered size="small" column={1}>
      {Object.entries(record).map(([field, value]) => (
        <Descriptions.Item key={field} label={STANDARD_FIELD_LABELS[field] || field}>
          <Typography.Text style={{ wordBreak: 'break-word' }}>{formatUnknownValue(value) || '-'}</Typography.Text>
        </Descriptions.Item>
      ))}
    </Descriptions>
  );
}

function renderErrorsByType(report?: IngestionPlanShadowRunReport) {
  if (!report?.errorsByType || !Object.keys(report.errorsByType).length) {
    return '-';
  }
  return (
    <Space size={[6, 6]} wrap>
      {Object.entries(report.errorsByType).map(([type, count]) => (
        <Typography.Text key={type} code>
          {type}: {count}
        </Typography.Text>
      ))}
    </Space>
  );
}

export default function IngestionPlanShadowRunReportDrawer({
  plan,
  run,
  formatTime,
  onClose,
}: IngestionPlanShadowRunReportDrawerProps) {
  const report = run?.report;
  const summary = report?.summary;
  return (
    <Drawer
      title={plan ? `试运行报告：${plan.candidateTable !== '-' ? plan.candidateTable : plan.name}` : '试运行报告'}
      width={960}
      open={Boolean(run)}
      onClose={onClose}
    >
      {run && (
        <div style={{ display: 'grid', gap: 16 }}>
          <Descriptions bordered size="small" column={{ xs: 1, sm: 1, md: 2 }}>
            <Descriptions.Item label="试运行结果">{shadowValidationResultTag(run.status)}</Descriptions.Item>
            <Descriptions.Item label="样本上限">{run.sampleLimit}</Descriptions.Item>
            <Descriptions.Item label="读取样本">{run.readCount}</Descriptions.Item>
            <Descriptions.Item label="转换成功">{run.successCount}</Descriptions.Item>
            <Descriptions.Item label="转换失败">{run.failedCount}</Descriptions.Item>
            <Descriptions.Item label="重复样本">{run.duplicateCount}</Descriptions.Item>
            <Descriptions.Item label="缺必填项">{run.missingRequiredCount}</Descriptions.Item>
            <Descriptions.Item label="耗时">{run.durationMs ?? '-'} ms</Descriptions.Item>
            <Descriptions.Item label="开始时间">{formatTime(run.startedAt)}</Descriptions.Item>
            <Descriptions.Item label="完成时间">{formatTime(run.finishedAt)}</Descriptions.Item>
          </Descriptions>

          {run.errorMessage && (
            <IngestionPlanDetailSection title="执行错误">
              <Typography.Text type="danger" style={{ wordBreak: 'break-word' }}>{run.errorMessage}</Typography.Text>
            </IngestionPlanDetailSection>
          )}

          <IngestionPlanDetailSection title="报告摘要">
            <Descriptions bordered size="small" column={{ xs: 1, sm: 1, md: 3 }}>
              <Descriptions.Item label="状态">{shadowValidationResultTag(summary?.status || report?.status || run.status)}</Descriptions.Item>
              <Descriptions.Item label="样本上限">{numberValue(summary?.sampleLimit)}</Descriptions.Item>
              <Descriptions.Item label="读取">{numberValue(summary?.readCount)}</Descriptions.Item>
              <Descriptions.Item label="成功">{numberValue(summary?.successCount)}</Descriptions.Item>
              <Descriptions.Item label="失败">{numberValue(summary?.failedCount)}</Descriptions.Item>
              <Descriptions.Item label="重复">{numberValue(summary?.duplicateCount)}</Descriptions.Item>
            </Descriptions>
          </IngestionPlanDetailSection>

          <IngestionPlanDetailSection title="警告">{renderTextTags(report?.warnings || [], '无')}</IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="阻断项">{renderTextTags(report?.blockers || [], '无')}</IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="失败原因统计">{renderErrorsByType(report)}</IngestionPlanDetailSection>

          <AdvancedDetailsCollapse title="技术校验项">
            {report?.checks?.length ? (
              <div style={{ display: 'grid', gap: 10 }}>
                {report.checks.map((check, index) => (
                  <div key={`${check.code}-${index}`} style={{ display: 'grid', gap: 4, paddingBottom: 10, borderBottom: '1px solid #edf2f7' }}>
                    <Space size={[6, 6]} wrap>
                      {shadowValidationResultTag(check.result)}
                      <Typography.Text strong style={{ wordBreak: 'break-word' }}>{check.code}</Typography.Text>
                    </Space>
                    {check.message && <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{check.message}</Typography.Text>}
                    {!!check.blockers?.length && <div>{renderTextTags(check.blockers)}</div>}
                    {check.details !== undefined && (
                      <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{formatUnknownValue(check.details)}</Typography.Text>
                    )}
                  </div>
                ))}
              </div>
            ) : '-'}
          </AdvancedDetailsCollapse>

          <IngestionPlanDetailSection title="样本预览">
            {report?.samples?.length ? (
              <div style={{ display: 'grid', gap: 14 }}>
                {report.samples.map((sample, index) => (
                  <div key={index} style={{ display: 'grid', gap: 10, padding: 12, background: '#fff', border: '1px solid #e4ebf4', borderRadius: 8 }}>
                    <Space size={[6, 6]} wrap>
                      <Typography.Text strong>样本 {index + 1}</Typography.Text>
                      {!!sample.errors?.length && shadowValidationResultTag('failed')}
                      {!!sample.warnings?.length && shadowValidationResultTag('warning')}
                    </Space>
                    {!!sample.errors?.length && <div>{renderTextTags(sample.errors)}</div>}
                    {!!sample.warnings?.length && <div>{renderTextTags(sample.warnings)}</div>}
                    <Typography.Text type="secondary">来源字段预览</Typography.Text>
                    {renderRecord(sample.sourcePreview)}
                    <Typography.Text type="secondary">标准化事件预览</Typography.Text>
                    {renderRecord(sample.standardEventPreview)}
                  </div>
                ))}
              </div>
            ) : '-'}
          </IngestionPlanDetailSection>

          <AdvancedDetailsCollapse title="预览策略">
            <Typography.Text style={{ wordBreak: 'break-word' }}>{formatUnknownValue(report?.previewPolicy) || '-'}</Typography.Text>
          </AdvancedDetailsCollapse>
        </div>
      )}
    </Drawer>
  );
}
