import { SettingOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Space, Tag, Tooltip, Typography } from 'antd';
import { useState } from 'react';
import type {
  IngestionPlanActivationRow,
  IngestionPlanMappingRuleUpdateRequest,
  IngestionPlanRow,
  IngestionPlanShadowRunRow,
  IngestionPlanSyncRunRow,
  IngestionPlanSyncScheduleRow,
  RuleDecisionAutoSummary,
} from '../../../types';
import {
  formatConfidence,
  planStatusTag,
  renderTextTags,
  renderWrappedTag,
  STANDARD_FIELD_LABELS,
} from '../utils/ingestionPlanLabels';
import {
  canActivatePlan,
  getActivationOperator,
  getActivationShadowRunId,
  getActivationStatus,
  getActivationTime,
  getShadowRunStatus,
  getSyncRunErrorMessage,
  getSyncRunMetric,
  getSyncRunStatus,
  getSyncRunTime,
  getSyncRunTriggerType,
  getSyncRunWarnings,
  getSyncScheduleMetric,
  getSyncScheduleStatus,
  getSyncScheduleText,
  getSyncScheduleTime,
} from '../utils/ingestionPlanActivation';
import AdvancedDetailsCollapse from '../../../components/AdvancedDetailsCollapse';
import { getCollectionRunTypeLabel, getPlanRuntimeStatus } from '../../../utils/businessDisplay';
import type { NormalizedIngestionPlan, NormalizedPlanMapping } from '../utils/normalizeIngestionPlan';
import IngestionPlanActions from './IngestionPlanActions';
import IngestionPlanDetailSection from './IngestionPlanDetailSection';
import IngestionPlanRuleConfigDrawer from './IngestionPlanRuleConfigDrawer';

interface IngestionPlanPanelProps {
  row: IngestionPlanRow;
  plan: NormalizedIngestionPlan;
  activation?: IngestionPlanActivationRow | null;
  latestShadowRun?: IngestionPlanShadowRunRow | null;
  syncRuns: IngestionPlanSyncRunRow[];
  syncSchedule?: IngestionPlanSyncScheduleRow | null;
  busy: boolean;
  formatTime: (value?: string | number) => string;
  onViewReason: (row: IngestionPlanRow) => void;
  onUpdateStatus: (row: IngestionPlanRow, status: string, successText: string) => void;
  onShadowValidate: (row: IngestionPlanRow) => void;
  onShadowRun: (row: IngestionPlanRow) => void;
  onViewShadowReport: (row: IngestionPlanRow) => void;
  onActivate: (row: IngestionPlanRow, latestShadowRun: IngestionPlanShadowRunRow) => void;
  onDeactivate: (activation: IngestionPlanActivationRow) => void;
  onSyncOnce: (row: IngestionPlanRow, activation: IngestionPlanActivationRow) => void;
  onConfigureSyncSchedule: (
    row: IngestionPlanRow,
    activation: IngestionPlanActivationRow,
    schedule?: IngestionPlanSyncScheduleRow | null,
  ) => void;
  onPauseSyncSchedule: (row: IngestionPlanRow, schedule: IngestionPlanSyncScheduleRow) => void;
  onResumeSyncSchedule: (row: IngestionPlanRow, schedule: IngestionPlanSyncScheduleRow) => void;
  onSaveMappingRule: (
    row: IngestionPlanRow,
    mapping: NormalizedPlanMapping,
    request: IngestionPlanMappingRuleUpdateRequest,
  ) => Promise<void>;
}

function syncRunStatusTag(value?: string) {
  const display = getPlanRuntimeStatus(value);
  return <Tag color={display.color}>{display.label}</Tag>;
}

function scheduleStatusTag(value?: string) {
  const display = getPlanRuntimeStatus(value);
  return <Tag color={display.color}>{display.label}</Tag>;
}

function valueMapCount(mapping: NormalizedPlanMapping) {
  const values = mapping.transformRulePayload?.values;
  return values && typeof values === 'object' && !Array.isArray(values)
    ? Object.keys(values).length
    : 0;
}

function ruleDecisionAutoSummary(summary?: RuleDecisionAutoSummary) {
  if (!summary) {
    return null;
  }
  return (
    <>
      {summary.status === 'warning' && (
        <Alert
          showIcon
          type="warning"
          message="规则自动评估存在异常，请查看同步报告。"
        />
      )}
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2, md: 4 }} title="规则自动评估">
        <Descriptions.Item label="状态">{syncRunStatusTag(summary.status)}</Descriptions.Item>
        <Descriptions.Item label="新增标准化事件">{summary.evaluatedStandardCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="规则评估">{summary.decisionCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="已命中">{summary.matchedCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="未命中">{summary.notMatchedCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="已跳过">{summary.skippedCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="异常">{summary.errorCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="评估失败事件">{summary.failedStandardCount ?? 0}</Descriptions.Item>
      </Descriptions>
    </>
  );
}

function syncRunSummary(run: IngestionPlanSyncRunRow | null, formatTime: (value?: string | number) => string) {
  if (!run) {
    return null;
  }
  const warnings = getSyncRunWarnings(run);
  return (
    <>
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2, md: 4 }} title={getSyncRunTriggerType(run) === 'scheduled' ? '最近定时同步结果' : '最近同步结果'}>
        <Descriptions.Item label="状态">{syncRunStatusTag(getSyncRunStatus(run))}</Descriptions.Item>
        <Descriptions.Item label="触发方式">{getCollectionRunTypeLabel(getSyncRunTriggerType(run) || 'manual')}</Descriptions.Item>
        <Descriptions.Item label="完成时间">{formatTime(getSyncRunTime(run))}</Descriptions.Item>
        <Descriptions.Item label="读取">{getSyncRunMetric(run, 'readCount', 'read_count')}</Descriptions.Item>
        <Descriptions.Item label="成功">{getSyncRunMetric(run, 'successCount', 'success_count')}</Descriptions.Item>
        <Descriptions.Item label="失败">{getSyncRunMetric(run, 'failedCount', 'failed_count')}</Descriptions.Item>
        <Descriptions.Item label="重复">{getSyncRunMetric(run, 'duplicateCount', 'duplicate_count')}</Descriptions.Item>
        <Descriptions.Item label="提醒">{warnings.length}</Descriptions.Item>
        <Descriptions.Item label="接收事件">{getSyncRunMetric(run, 'rawCount', 'raw_count')}</Descriptions.Item>
        <Descriptions.Item label="标准化事件">{getSyncRunMetric(run, 'standardCount', 'standard_count')}</Descriptions.Item>
        <Descriptions.Item label="提醒项" span={2}>
          {warnings.length ? renderTextTags(warnings) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="错误信息" span={2}>{getSyncRunErrorMessage(run) || '-'}</Descriptions.Item>
      </Descriptions>
      <AdvancedDetailsCollapse
        title="同步技术详情"
        items={[
          { label: 'status', value: getSyncRunStatus(run) },
          { label: 'triggerType', value: getSyncRunTriggerType(run) || 'manual' },
          { label: 'rawCount', value: getSyncRunMetric(run, 'rawCount', 'raw_count') },
          { label: 'standardCount', value: getSyncRunMetric(run, 'standardCount', 'standard_count') },
        ]}
      />
      {ruleDecisionAutoSummary(run.report?.ruleDecisionAuto)}
    </>
  );
}

export default function IngestionPlanPanel({
  row,
  plan,
  activation,
  latestShadowRun,
  syncRuns,
  syncSchedule,
  busy,
  formatTime,
  onViewReason,
  onUpdateStatus,
  onShadowValidate,
  onShadowRun,
  onViewShadowReport,
  onActivate,
  onDeactivate,
  onSyncOnce,
  onConfigureSyncSchedule,
  onPauseSyncSchedule,
  onResumeSyncSchedule,
  onSaveMappingRule,
}: IngestionPlanPanelProps) {
  const [ruleMapping, setRuleMapping] = useState<NormalizedPlanMapping | null>(null);
  const activationStatus = getActivationStatus(activation);
  const activationShadowRunId = getActivationShadowRunId(activation);
  const activationOperator = getActivationOperator(activation);
  const activationTime = getActivationTime(activation);
  const latestShadowRunStatus = getShadowRunStatus(latestShadowRun);
  const latestSyncRun = syncRuns[0] ?? null;
  const latestScheduledRun = syncRuns.find((run) => getSyncRunTriggerType(run) === 'scheduled') ?? null;
  const isActive = activationStatus === 'active';
  const canActivate = canActivatePlan(plan.status, latestShadowRun, activation);
  const activationHint = isActive
    ? '当前方案已启用。停用只会更新启用记录，不会改变方案审核状态。'
    : canActivate
      ? '最新试运行已通过。启用后仅生成启用审计记录，不会立即采集数据或产生告警。'
      : '未启用。只有最新试运行通过，且方案已批准或已进入试运行准备时才允许启用。';

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
            activation={activation}
            latestShadowRun={latestShadowRun}
            syncSchedule={syncSchedule}
            busy={busy}
            onViewReason={onViewReason}
            onUpdateStatus={onUpdateStatus}
            onShadowValidate={onShadowValidate}
            onShadowRun={onShadowRun}
            onViewShadowReport={onViewShadowReport}
            onActivate={onActivate}
            onDeactivate={onDeactivate}
            onSyncOnce={onSyncOnce}
            onConfigureSyncSchedule={onConfigureSyncSchedule}
            onPauseSyncSchedule={onPauseSyncSchedule}
            onResumeSyncSchedule={onResumeSyncSchedule}
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
        <Descriptions.Item label="接入对象">{plan.candidateTable}</Descriptions.Item>
        <Descriptions.Item label="生成时间">{formatTime(plan.generatedAt)}</Descriptions.Item>
        <Descriptions.Item label="当前状态">{planStatusTag(plan.status)}</Descriptions.Item>
        <Descriptions.Item label="启用状态">{scheduleStatusTag(isActive ? 'active' : 'inactive')}</Descriptions.Item>
        <Descriptions.Item label="启用人">{activationOperator || '-'}</Descriptions.Item>
        <Descriptions.Item label="启用时间">{formatTime(activationTime)}</Descriptions.Item>
        <Descriptions.Item label="关联试运行">{activationShadowRunId ? '已关联' : '-'}</Descriptions.Item>
        <Descriptions.Item label="最新试运行">{syncRunStatusTag(latestShadowRunStatus)}</Descriptions.Item>
      </Descriptions>
      <AdvancedDetailsCollapse
        title="启用技术详情"
        items={[
          { label: 'templateType', value: plan.templateType },
          { label: 'generationVersion', value: plan.generationVersion },
          { label: 'activationStatus', value: activationStatus },
          { label: 'shadowRunId', value: activationShadowRunId },
          { label: 'latestShadowRunStatus', value: latestShadowRunStatus },
        ]}
      />

      <Alert
        showIcon
        type={isActive ? 'success' : canActivate ? 'info' : 'warning'}
        message={isActive ? '方案已启用' : '方案未启用'}
        description={activationHint}
      />

      {isActive && (
        <Alert
          showIcon
          type="info"
          message="同步边界"
          description="立即同步和定时同步只负责接收外部事件、完成标准化并执行规则评估；不会直接创建告警，也不会触发通知。"
        />
      )}

      {isActive && syncSchedule && (
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2, md: 4 }} title="定时同步">
          <Descriptions.Item label="状态">{scheduleStatusTag(getSyncScheduleStatus(syncSchedule))}</Descriptions.Item>
          <Descriptions.Item label="间隔">{getSyncScheduleMetric(syncSchedule, 'intervalSeconds', 'interval_seconds')} 秒</Descriptions.Item>
          <Descriptions.Item label="样本上限">{getSyncScheduleMetric(syncSchedule, 'sampleLimit', 'sample_limit')}</Descriptions.Item>
          <Descriptions.Item label="连续失败">{getSyncScheduleMetric(syncSchedule, 'consecutiveFailures', 'consecutive_failures')}</Descriptions.Item>
          <Descriptions.Item label="下次执行">{formatTime(getSyncScheduleTime(syncSchedule, 'nextRunAt', 'next_run_at'))}</Descriptions.Item>
          <Descriptions.Item label="上次执行">{formatTime(getSyncScheduleTime(syncSchedule, 'lastRunAt', 'last_run_at'))}</Descriptions.Item>
          <Descriptions.Item label="上次状态">{syncRunStatusTag(getSyncScheduleText(syncSchedule, 'lastStatus', 'last_status'))}</Descriptions.Item>
          <Descriptions.Item label="最近同步">{getSyncScheduleMetric(syncSchedule, 'lastSyncRunId', 'last_sync_run_id') ? '已记录' : '-'}</Descriptions.Item>
          <Descriptions.Item label="上次错误" span={4}>
            {getSyncScheduleText(syncSchedule, 'lastErrorMessage', 'last_error_message') || '-'}
          </Descriptions.Item>
        </Descriptions>
      )}

      {syncRunSummary(latestSyncRun, formatTime)}
      {latestScheduledRun && latestScheduledRun.id !== latestSyncRun?.id && syncRunSummary(latestScheduledRun, formatTime)}

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
                  {mapping.transformRule && <Typography.Text type="secondary" style={{ wordBreak: 'break-word' }}>已配置转换规则</Typography.Text>}
                  {mapping.transformRule === 'valueMap' && valueMapCount(mapping) > 0 && (
                    <Tag color="blue">{valueMapCount(mapping)} 个映射值</Tag>
                  )}
                  <Tooltip title={isActive ? '已启用方案不能直接修改' : '配置转换规则'}>
                    <Button
                      size="small"
                      icon={<SettingOutlined />}
                      disabled={isActive}
                      onClick={() => setRuleMapping(mapping)}
                    >
                      规则
                    </Button>
                  </Tooltip>
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
      <IngestionPlanRuleConfigDrawer
        open={Boolean(ruleMapping)}
        mapping={ruleMapping}
        active={isActive}
        saving={busy}
        onClose={() => setRuleMapping(null)}
        onSave={(request) => ruleMapping ? onSaveMappingRule(row, ruleMapping, request) : Promise.resolve()}
      />
    </div>
  );
}
