import { Descriptions, Drawer, Space, Typography } from 'antd';
import {
  formatConfidence,
  planStatusTag,
  renderTextTags,
  renderWrappedTag,
  STANDARD_FIELD_LABELS,
} from '../utils/ingestionPlanLabels';
import type { NormalizedIngestionPlan, NormalizedSignalEvidence } from '../utils/normalizeIngestionPlan';
import IngestionPlanDetailSection from './IngestionPlanDetailSection';

interface IngestionPlanReasonDrawerProps {
  plan: NormalizedIngestionPlan | null;
  open: boolean;
  formatTime: (value?: string | number) => string;
  onClose: () => void;
}

const SIGNAL_LABELS: Record<string, string> = {
  occurred_at: '发生时间',
  severity: '风险等级',
  actor: '账号 / 操作人',
  asset_ref: '资产 / 终端',
  title: '告警标题',
  external_id: '外部事件编号',
  policy_name: '策略 / 规则',
  result: '处理结果',
  subject_ref: '目标对象',
};

const SIGNAL_SOURCE_LABELS: Record<string, string> = {
  field_name: '字段名',
  table_name: '表名',
  category: '分类',
  field_type: '字段类型',
  sample_value: '样本值',
};

function renderSignalName(signal: string) {
  return SIGNAL_LABELS[signal] || STANDARD_FIELD_LABELS[signal] || signal || '-';
}

function renderSignalSource(source: string) {
  return SIGNAL_SOURCE_LABELS[source] || source || '-';
}

function renderSignalEvidence(evidence: NormalizedSignalEvidence[]) {
  return (
    <div style={{ display: 'grid', gap: 10 }}>
      {evidence.map((item) => (
        <div key={item.key} style={{ display: 'grid', gap: 6, paddingBottom: 10, borderBottom: '1px solid #edf2f7' }}>
          <Space size={[6, 6]} wrap>
            {renderWrappedTag(renderSignalName(item.signal), 'processing')}
            {renderWrappedTag(renderSignalSource(item.source), 'cyan')}
          </Space>
          <div>
            <Typography.Text type="secondary">来源字段：</Typography.Text>
            {renderTextTags(item.sourceFields, '未返回')}
          </div>
        </div>
      ))}
    </div>
  );
}

function renderTemplateSignalSection(plan: NormalizedIngestionPlan) {
  if (plan.signalEvidence.length) {
    return (
      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        {renderSignalEvidence(plan.signalEvidence)}
        {!!plan.missingSignals.length && (
          <div>
            <Typography.Text type="secondary">缺失：</Typography.Text>
            {renderTextTags(plan.missingSignals.map(renderSignalName), '无')}
          </div>
        )}
      </Space>
    );
  }
  if (plan.matchedSignals.length || plan.missingSignals.length) {
    return (
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <div>
          <Typography.Text type="secondary">已命中：</Typography.Text>
          {renderTextTags(plan.matchedSignals.map(renderSignalName), '无')}
        </div>
        <div>
          <Typography.Text type="secondary">缺失：</Typography.Text>
          {renderTextTags(plan.missingSignals.map(renderSignalName), '无')}
        </div>
      </Space>
    );
  }
  return '-';
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
            ) : '系统暂未返回详细原因'}
          </IngestionPlanDetailSection>
          <IngestionPlanDetailSection title="模板信号依据">
            {renderTemplateSignalSection(plan)}
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
                    <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>
                      {mapping.reason || (mapping.transformRule ? '已配置转换规则' : '未返回单字段原因')}
                    </Typography.Text>
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
