import { Descriptions, Space, Typography } from 'antd';
import type { IngestionPlanRow } from '../../../types';
import {
  formatConfidence,
  planStatusTag,
  renderTextTags,
  renderWrappedTag,
  STANDARD_FIELD_LABELS,
} from '../utils/ingestionPlanLabels';
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';
import IngestionPlanActions from './IngestionPlanActions';
import IngestionPlanDetailSection from './IngestionPlanDetailSection';

interface IngestionPlanPanelProps {
  row: IngestionPlanRow;
  plan: NormalizedIngestionPlan;
  busy: boolean;
  formatTime: (value?: string | number) => string;
  onViewReason: (row: IngestionPlanRow) => void;
  onUpdateStatus: (row: IngestionPlanRow, status: string, successText: string) => void;
  onShadowValidate: (row: IngestionPlanRow) => void;
}

export default function IngestionPlanPanel({
  row,
  plan,
  busy,
  formatTime,
  onViewReason,
  onUpdateStatus,
  onShadowValidate,
}: IngestionPlanPanelProps) {
  return (
    <div
      style={{
        display: 'grid',
        gap: 14,
        minWidth: 0,
        padding: 14,
        background: '#f8fafc',
        border: '1px solid #e4ebf4',
        borderRadius: 10,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', minWidth: 0 }}>
        <div style={{ minWidth: 220, flex: '1 1 320px' }}>
          <Typography.Text strong style={{ display: 'block', fontSize: 15, wordBreak: 'break-word' }}>
            {plan.candidateTable !== '-' ? plan.candidateTable : plan.name}
          </Typography.Text>
          <Space size={[6, 6]} wrap style={{ marginTop: 6 }}>
            {planStatusTag(plan.status)}
            {renderWrappedTag(plan.templateType)}
            <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{plan.dataSourceName}</Typography.Text>
          </Space>
        </div>
        <div style={{ flex: '1 1 360px', textAlign: 'right' }}>
          <IngestionPlanActions
            row={row}
            plan={plan}
            busy={busy}
            onViewReason={onViewReason}
            onUpdateStatus={onUpdateStatus}
            onShadowValidate={onShadowValidate}
          />
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(128px, 1fr))', gap: 10 }}>
        {[
          ['综合置信度', formatConfidence(plan.overallConfidence)],
          ['模板置信度', formatConfidence(plan.templateConfidence)],
          ['覆盖置信度', formatConfidence(plan.coverageConfidence)],
          ['映射完整度', formatConfidence(plan.mappingCompleteness)],
        ].map(([label, value]) => (
          <div key={label} style={{ minWidth: 0, padding: '10px 12px', background: '#fff', border: '1px solid #e4ebf4', borderRadius: 8 }}>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>{label}</Typography.Text>
            <Typography.Text strong style={{ fontSize: 18 }}>{value}</Typography.Text>
          </div>
        ))}
      </div>

      <Descriptions bordered size="small" column={{ xs: 1, sm: 1, md: 2 }}>
        <Descriptions.Item label="候选表">{plan.candidateTable}</Descriptions.Item>
        <Descriptions.Item label="模板类型">{plan.templateType}</Descriptions.Item>
        <Descriptions.Item label="生成版本">{plan.generationVersion}</Descriptions.Item>
        <Descriptions.Item label="生成时间">{formatTime(plan.generatedAt)}</Descriptions.Item>
        <Descriptions.Item label="当前状态">{planStatusTag(plan.status)}</Descriptions.Item>
      </Descriptions>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 14 }}>
        <IngestionPlanDetailSection title="字段映射">
          {plan.fieldMappings.length ? (
            <div style={{ display: 'grid', gap: 8 }}>
              {plan.fieldMappings.map((mapping) => (
                <div key={mapping.key} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', minWidth: 0 }}>
                  {renderWrappedTag(mapping.sourceField || '-', 'processing')}
                  <span>映射到</span>
                  {renderWrappedTag(STANDARD_FIELD_LABELS[mapping.standardField] || mapping.standardField || '-', 'success')}
                  {mapping.confidence !== undefined && <Typography.Text type="secondary">{formatConfidence(mapping.confidence)}</Typography.Text>}
                  {mapping.transformRule && <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>{mapping.transformRule}</Typography.Text>}
                </div>
              ))}
            </div>
          ) : '-'}
        </IngestionPlanDetailSection>
        <IngestionPlanDetailSection title="去重策略">
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            <div>字段：{renderTextTags(plan.dedupFields, '未配置')}</div>
            {plan.dedupRule && <div>规则：{plan.dedupRule}</div>}
            {plan.dedupWindow && <div>窗口：{plan.dedupWindow}</div>}
          </Space>
        </IngestionPlanDetailSection>
        <IngestionPlanDetailSection title="缺失字段">{renderTextTags(plan.missingFields, '无')}</IngestionPlanDetailSection>
        <IngestionPlanDetailSection title="风险提示">{renderTextTags(plan.risks, '无')}</IngestionPlanDetailSection>
        <IngestionPlanDetailSection title="推荐动作">{renderTextTags(plan.recommendedActions, '无')}</IngestionPlanDetailSection>
      </div>
    </div>
  );
}
