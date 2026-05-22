import { Descriptions, Drawer, Space, Typography } from 'antd';
import {
  formatConfidence,
  planStatusTag,
  renderTextTags,
  renderWrappedTag,
  STANDARD_FIELD_LABELS,
} from '../utils/ingestionPlanLabels';
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';
import IngestionPlanDetailSection from './IngestionPlanDetailSection';

interface IngestionPlanReasonDrawerProps {
  plan: NormalizedIngestionPlan | null;
  open: boolean;
  formatTime: (value?: string | number) => string;
  onClose: () => void;
}

export default function IngestionPlanReasonDrawer({
  plan,
  open,
  formatTime,
  onClose,
}: IngestionPlanReasonDrawerProps) {
  return (
    <Drawer
      title={plan ? `推荐原因：${plan.candidateTable !== '-' ? plan.candidateTable : plan.name}` : '推荐原因'}
      width={860}
      open={open}
      onClose={onClose}
    >
      {plan && (
        <div style={{ display: 'grid', gap: 16 }}>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label="候选表">{plan.candidateTable}</Descriptions.Item>
            <Descriptions.Item label="模板类型">{plan.templateType}</Descriptions.Item>
            <Descriptions.Item label="综合置信度">{formatConfidence(plan.overallConfidence)}</Descriptions.Item>
            <Descriptions.Item label="当前状态">{planStatusTag(plan.status)}</Descriptions.Item>
            <Descriptions.Item label="生成版本">{plan.generationVersion}</Descriptions.Item>
            <Descriptions.Item label="生成时间">{formatTime(plan.generatedAt)}</Descriptions.Item>
          </Descriptions>

          <IngestionPlanDetailSection title="判断依据">
            {plan.reasons.length ? (
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {plan.reasons.map((reason) => <li key={reason}>{reason}</li>)}
              </ul>
            ) : '后端未返回详细原因'}
          </IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="字段映射原因">
            {plan.fieldMappings.length ? (
              <div style={{ display: 'grid', gap: 10 }}>
                {plan.fieldMappings.map((mapping) => (
                  <div key={mapping.key} style={{ display: 'grid', gap: 4, paddingBottom: 10, borderBottom: '1px solid #edf2f7' }}>
                    <Space size={[6, 6]} wrap>
                      {renderWrappedTag(mapping.sourceField || '-', 'processing')}
                      <span>映射到</span>
                      {renderWrappedTag(STANDARD_FIELD_LABELS[mapping.standardField] || mapping.standardField || '-', 'success')}
                      {mapping.confidence !== undefined && <Typography.Text type="secondary">{formatConfidence(mapping.confidence)}</Typography.Text>}
                    </Space>
                    <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{mapping.reason || mapping.transformRule || '未返回单字段原因'}</Typography.Text>
                  </div>
                ))}
              </div>
            ) : '-'}
          </IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="风险提示">{renderTextTags(plan.risks, '无')}</IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="推荐动作">{renderTextTags(plan.recommendedActions, '无')}</IngestionPlanDetailSection>
        </div>
      )}
    </Drawer>
  );
}
