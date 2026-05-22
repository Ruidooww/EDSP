import { Button, Card, Select, Space } from 'antd';
import type { IngestionPlanRow } from '../../../types';
import { PLAN_STATUS_FILTER_OPTIONS } from '../utils/ingestionPlanLabels';
import type { NormalizedIngestionPlan } from '../utils/normalizeIngestionPlan';
import IngestionPlanPanel from './IngestionPlanPanel';

interface IngestionPlanSourceOption {
  label: string;
  value: number;
}

export interface IngestionPlanViewRow {
  row: IngestionPlanRow;
  plan: NormalizedIngestionPlan;
}

interface IngestionPlanSectionProps {
  sourceOptions: IngestionPlanSourceOption[];
  sourceId?: number;
  status?: string;
  rows: IngestionPlanViewRow[];
  loading: boolean;
  generating: boolean;
  actionId: number | null;
  formatTime: (value?: string | number) => string;
  onSourceChange: (value?: number) => void;
  onStatusChange: (value?: string) => void;
  onRefresh: () => void;
  onGenerate: () => void;
  onViewReason: (row: IngestionPlanRow) => void;
  onUpdateStatus: (row: IngestionPlanRow, status: string, successText: string) => void;
  onShadowValidate: (row: IngestionPlanRow) => void;
}

export default function IngestionPlanSection({
  sourceOptions,
  sourceId,
  status,
  rows,
  loading,
  generating,
  actionId,
  formatTime,
  onSourceChange,
  onStatusChange,
  onRefresh,
  onGenerate,
  onViewReason,
  onUpdateStatus,
  onShadowValidate,
}: IngestionPlanSectionProps) {
  return (
    <Card className="ops-card" title="推荐接入方案">
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        <Space size={[8, 8]} wrap>
          <Select
            allowClear
            placeholder="全部数据源"
            options={sourceOptions}
            value={sourceId}
            style={{ minWidth: 220 }}
            onChange={onSourceChange}
          />
          <Select
            allowClear
            placeholder="全部状态"
            options={PLAN_STATUS_FILTER_OPTIONS}
            value={status}
            style={{ minWidth: 150 }}
            onChange={onStatusChange}
          />
        </Space>
        <Space size={[8, 8]} wrap>
          <Button loading={loading} onClick={onRefresh}>
            刷新方案
          </Button>
          <Button type="primary" loading={generating} disabled={!sourceId} onClick={onGenerate}>
            生成推荐方案
          </Button>
        </Space>
      </div>

      {rows.length ? (
        <div style={{ display: 'grid', gap: 14, minWidth: 0 }}>
          {rows.map(({ row, plan }) => (
            <IngestionPlanPanel
              key={row.id}
              row={row}
              plan={plan}
              busy={actionId === row.id}
              formatTime={formatTime}
              onViewReason={onViewReason}
              onUpdateStatus={onUpdateStatus}
              onShadowValidate={onShadowValidate}
            />
          ))}
        </div>
      ) : (
        <div style={{ padding: 24, textAlign: 'center', color: '#7a8798', background: '#f8fafc', border: '1px dashed #d7e0ec', borderRadius: 10 }}>
          {loading ? '推荐接入方案加载中' : '暂无推荐接入方案'}
        </div>
      )}
    </Card>
  );
}
