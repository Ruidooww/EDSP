import {
  FileSearchOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PoweroffOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { Button, Space } from 'antd';
import type {
  IngestionPlanActivationRow,
  IngestionPlanRow,
  IngestionPlanShadowRunRow,
  IngestionPlanSyncScheduleRow,
} from '../../../types';
import { canActivatePlan, getActivationStatus, getSyncScheduleStatus } from '../utils/ingestionPlanActivation';
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';

interface IngestionPlanActionsProps {
  row: IngestionPlanRow;
  plan: NormalizedIngestionPlan;
  activation?: IngestionPlanActivationRow | null;
  latestShadowRun?: IngestionPlanShadowRunRow | null;
  syncSchedule?: IngestionPlanSyncScheduleRow | null;
  busy: boolean;
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

export default function IngestionPlanActions({
  row,
  plan,
  activation,
  latestShadowRun,
  syncSchedule,
  busy,
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
}: IngestionPlanActionsProps) {
  const status = plan.status;
  const isActive = getActivationStatus(activation) === 'active';
  const canReview = status === 'suggested';
  const canApprove = status === 'suggested' || status === 'review_required';
  const canReject = status === 'suggested' || status === 'review_required';
  const canPrepareShadow = status === 'approved';
  const canShadowValidate = status === 'approved' || status === 'shadow_ready';
  const canShadowRun = status === 'approved' || status === 'shadow_ready';
  const canDiscard = status === 'approved' || status === 'shadow_ready';
  const canActivate = canActivatePlan(status, latestShadowRun, activation);
  const syncScheduleStatus = getSyncScheduleStatus(syncSchedule);

  return (
    <Space size={[4, 6]} wrap style={{ justifyContent: 'flex-end' }}>
      <Button size="small" type="link" onClick={() => onViewReason(row)}>
        查看原因
      </Button>
      {canReview && (
        <Button size="small" loading={busy} onClick={() => onUpdateStatus(row, 'review_required', '推荐方案已标记复核')}>
          标记复核
        </Button>
      )}
      {canApprove && (
        <Button size="small" type="primary" loading={busy} onClick={() => onUpdateStatus(row, 'approved', '推荐方案已批准')}>
          批准方案
        </Button>
      )}
      {canPrepareShadow && (
        <Button size="small" loading={busy} onClick={() => onUpdateStatus(row, 'shadow_ready', '推荐方案已进入试运行准备')}>
          进入试运行准备
        </Button>
      )}
      {canShadowValidate && (
        <Button size="small" icon={<SafetyCertificateOutlined />} loading={busy} onClick={() => onShadowValidate(row)}>
          Shadow Precheck
        </Button>
      )}
      {canShadowRun && (
        <Button size="small" icon={<PlayCircleOutlined />} loading={busy} onClick={() => onShadowRun(row)}>
          执行试运行
        </Button>
      )}
      {canShadowRun && (
        <Button size="small" icon={<FileSearchOutlined />} loading={busy} onClick={() => onViewShadowReport(row)}>
          查看试运行报告
        </Button>
      )}
      {canActivate && latestShadowRun && (
        <Button size="small" type="primary" icon={<PoweroffOutlined />} loading={busy} onClick={() => onActivate(row, latestShadowRun)}>
          启用方案
        </Button>
      )}
      {isActive && activation && (
        <Button size="small" icon={<SyncOutlined />} loading={busy} onClick={() => onSyncOnce(row, activation)}>
          手动同步一次
        </Button>
      )}
      {isActive && activation && (
        <Button size="small" icon={<ScheduleOutlined />} loading={busy} onClick={() => onConfigureSyncSchedule(row, activation, syncSchedule)}>
          {syncSchedule ? '配置定时同步' : '启用定时同步'}
        </Button>
      )}
      {isActive && syncSchedule && syncScheduleStatus === 'enabled' && (
        <Button size="small" icon={<PauseCircleOutlined />} loading={busy} onClick={() => onPauseSyncSchedule(row, syncSchedule)}>
          暂停定时同步
        </Button>
      )}
      {isActive && syncSchedule && syncScheduleStatus === 'paused' && (
        <Button size="small" icon={<PlayCircleOutlined />} loading={busy} onClick={() => onResumeSyncSchedule(row, syncSchedule)}>
          恢复定时同步
        </Button>
      )}
      {isActive && activation && (
        <Button size="small" danger icon={<PoweroffOutlined />} loading={busy} onClick={() => onDeactivate(activation)}>
          停用
        </Button>
      )}
      {canReject && (
        <Button size="small" danger loading={busy} onClick={() => onUpdateStatus(row, 'rejected', '推荐方案已拒绝')}>
          拒绝
        </Button>
      )}
      {canDiscard && (
        <Button size="small" danger loading={busy} onClick={() => onUpdateStatus(row, 'rejected', '推荐方案已废弃')}>
          废弃方案
        </Button>
      )}
    </Space>
  );
}
