import { Alert, Descriptions, Space, Tag, Typography } from 'antd';
import type {
  IngestionPlanActivationRow,
  IngestionPlanRow,
  IngestionPlanShadowRunRow,
  IngestionPlanSyncRunRow,
  IngestionPlanSyncScheduleRow,
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
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';
import IngestionPlanActions from './IngestionPlanActions';
import IngestionPlanDetailSection from './IngestionPlanDetailSection';

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
}

function syncRunStatusTag(value?: string) {
  if (value === 'passed') {
    return <Tag color="success">passed</Tag>;
  }
  if (value === 'warning') {
    return <Tag color="warning">warning</Tag>;
  }
  if (value === 'blocked') {
    return <Tag color="error">blocked</Tag>;
  }
  if (value === 'failed') {
    return <Tag color="error">failed</Tag>;
  }
  return <Tag>{value || '-'}</Tag>;
}

function scheduleStatusTag(value?: string) {
  if (value === 'enabled') {
    return <Tag color="processing">enabled</Tag>;
  }
  if (value === 'paused') {
    return <Tag>paused</Tag>;
  }
  return <Tag>{value || '-'}</Tag>;
}

function syncRunSummary(run: IngestionPlanSyncRunRow | null, formatTime: (value?: string | number) => string) {
  if (!run) {
    return null;
  }
  const warnings = getSyncRunWarnings(run);
  return (
    <Descriptions bordered size="small" column={{ xs: 1, sm: 2, md: 4 }} title={getSyncRunTriggerType(run) === 'scheduled' ? '最近定时同步结果' : '最近同步结果'}>
      <Descriptions.Item label="状态">{syncRunStatusTag(getSyncRunStatus(run))}</Descriptions.Item>
      <Descriptions.Item label="触发">{getSyncRunTriggerType(run) || 'manual'}</Descriptions.Item>
      <Descriptions.Item label="完成时间">{formatTime(getSyncRunTime(run))}</Descriptions.Item>
      <Descriptions.Item label="读取">{getSyncRunMetric(run, 'readCount', 'read_count')}</Descriptions.Item>
      <Descriptions.Item label="成功">{getSyncRunMetric(run, 'successCount', 'success_count')}</Descriptions.Item>
      <Descriptions.Item label="失败">{getSyncRunMetric(run, 'failedCount', 'failed_count')}</Descriptions.Item>
      <Descriptions.Item label="重复">{getSyncRunMetric(run, 'duplicateCount', 'duplicate_count')}</Descriptions.Item>
      <Descriptions.Item label="warning">{warnings.length}</Descriptions.Item>
      <Descriptions.Item label="raw_events">{getSyncRunMetric(run, 'rawCount', 'raw_count')}</Descriptions.Item>
      <Descriptions.Item label="standard_events">{getSyncRunMetric(run, 'standardCount', 'standard_count')}</Descriptions.Item>
      <Descriptions.Item label="warnings" span={2}>
        {warnings.length ? renderTextTags(warnings) : '-'}
      </Descriptions.Item>
      <Descriptions.Item label="错误信息" span={2}>{getSyncRunErrorMessage(run) || '-'}</Descriptions.Item>
    </Descriptions>
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
}: IngestionPlanPanelProps) {
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
    ? '当前方案已启用。停用只会更新 activation 记录，不会变更 Precheck / Shadow Run / 方案状态。'
    : canActivate
      ? '最新 Shadow Run 已通过。启用后仅生成启用审计记录，不会立即采集数据或产生告警。'
      : '未启用。只有最新 Shadow Run status 为 passed，且方案状态为 approved 或 shadow_ready 时才允许启用。';

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
        <Descriptions.Item label="候选表">{plan.candidateTable}</Descriptions.Item>
        <Descriptions.Item label="模板类型">{plan.templateType}</Descriptions.Item>
        <Descriptions.Item label="生成版本">{plan.generationVersion}</Descriptions.Item>
        <Descriptions.Item label="生成时间">{formatTime(plan.generatedAt)}</Descriptions.Item>
        <Descriptions.Item label="当前状态">{planStatusTag(plan.status)}</Descriptions.Item>
        <Descriptions.Item label="启用状态">{isActive ? <Tag color="success">active</Tag> : <Tag>inactive</Tag>}</Descriptions.Item>
        <Descriptions.Item label="启用人">{activationOperator || '-'}</Descriptions.Item>
        <Descriptions.Item label="启用时间">{formatTime(activationTime)}</Descriptions.Item>
        <Descriptions.Item label="关联 shadowRunId">{activationShadowRunId ? `#${activationShadowRunId}` : '-'}</Descriptions.Item>
        <Descriptions.Item label="最新 Shadow Run">{latestShadowRunStatus || '-'}</Descriptions.Item>
      </Descriptions>

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
          description="手动同步和定时同步会写入 raw_events / standard_events；不会写入 alert_decisions / alerts，也不会触发通知。"
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
          <Descriptions.Item label="上次状态">{getSyncScheduleText(syncSchedule, 'lastStatus', 'last_status') || '-'}</Descriptions.Item>
          <Descriptions.Item label="最近 run">{getSyncScheduleMetric(syncSchedule, 'lastSyncRunId', 'last_sync_run_id') || '-'}</Descriptions.Item>
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
