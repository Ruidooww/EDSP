import { FileSearchOutlined, PlayCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Button, Space } from 'antd';
import type { IngestionPlanRow } from '../../../types';
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';

interface IngestionPlanActionsProps {
  row: IngestionPlanRow;
  plan: NormalizedIngestionPlan;
  busy: boolean;
  onViewReason: (row: IngestionPlanRow) => void;
  onUpdateStatus: (row: IngestionPlanRow, status: string, successText: string) => void;
  onShadowValidate: (row: IngestionPlanRow) => void;
  onShadowRun: (row: IngestionPlanRow) => void;
  onViewShadowReport: (row: IngestionPlanRow) => void;
}

export default function IngestionPlanActions({
  row,
  plan,
  busy,
  onViewReason,
  onUpdateStatus,
  onShadowValidate,
  onShadowRun,
  onViewShadowReport,
}: IngestionPlanActionsProps) {
  const status = plan.status;
  const canReview = status === 'suggested';
  const canApprove = status === 'suggested' || status === 'review_required';
  const canReject = status === 'suggested' || status === 'review_required';
  const canPrepareShadow = status === 'approved';
  const canShadowValidate = status === 'approved' || status === 'shadow_ready';
  const canShadowRun = status === 'approved' || status === 'shadow_ready';
  const canDiscard = status === 'approved' || status === 'shadow_ready';

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
          试运行前校验 / Shadow Precheck
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
