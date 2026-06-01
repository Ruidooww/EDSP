import { Descriptions, Drawer, Space, Typography } from 'antd';
import type { IngestionPlanShadowValidationReport } from '../../../types';
import {
  formatUnknownValue,
  planStatusTag,
  renderTextTags,
  shadowValidationResultTag,
  STANDARD_FIELD_LABELS,
} from '../utils/ingestionPlanLabels';
import AdvancedDetailsCollapse from '../../../components/AdvancedDetailsCollapse';
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';
import IngestionPlanDetailSection from './IngestionPlanDetailSection';

interface IngestionPlanPrecheckDrawerProps {
  plan: NormalizedIngestionPlan | null;
  report: IngestionPlanShadowValidationReport | null;
  formatTime: (value?: string | number) => string;
  onClose: () => void;
}

export default function IngestionPlanPrecheckDrawer({
  plan,
  report,
  formatTime,
  onClose,
}: IngestionPlanPrecheckDrawerProps) {
  return (
    <Drawer
      title={plan ? `试运行前校验：${plan.candidateTable !== '-' ? plan.candidateTable : plan.name}` : '试运行前校验'}
      width={900}
      open={Boolean(report)}
      onClose={onClose}
    >
      {report && (
        <div style={{ display: 'grid', gap: 16 }}>
          <Descriptions bordered size="small" column={{ xs: 1, sm: 1, md: 2 }}>
            <Descriptions.Item label="校验结果">{shadowValidationResultTag(report.result)}</Descriptions.Item>
            <Descriptions.Item label="方案状态">{planStatusTag(report.planStatus)}</Descriptions.Item>
            <Descriptions.Item label="推荐状态">{report.statusRecommendation || '-'}</Descriptions.Item>
            <Descriptions.Item label="样本上限">{report.sampleLimit ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="候选表">{report.mainTable || '-'}</Descriptions.Item>
            <Descriptions.Item label="模板类型">{report.templateKey || '-'}</Descriptions.Item>
            <Descriptions.Item label="映射字段数">{report.mappedFieldCount ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="校验时间">{formatTime(report.checkedAt)}</Descriptions.Item>
          </Descriptions>

          <IngestionPlanDetailSection title="阻断项">{renderTextTags(report.blockers || [], '无')}</IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="提醒项">{renderTextTags(report.warnings || [], '无')}</IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="标准化事件预览">
            {report.standardEventPreview && Object.keys(report.standardEventPreview).length ? (
              <Descriptions bordered size="small" column={1}>
                {Object.entries(report.standardEventPreview).map(([field, value]) => (
                  <Descriptions.Item key={field} label={STANDARD_FIELD_LABELS[field] || field}>
                    <Typography.Text style={{ wordBreak: 'break-word' }}>{formatUnknownValue(value) || '-'}</Typography.Text>
                  </Descriptions.Item>
                ))}
              </Descriptions>
            ) : '-'}
          </IngestionPlanDetailSection>
          <AdvancedDetailsCollapse title="技术校验项">
            {report.checks?.length ? (
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
        </div>
      )}
    </Drawer>
  );
}
