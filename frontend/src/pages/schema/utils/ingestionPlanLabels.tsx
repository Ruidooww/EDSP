import { Space, Tag } from 'antd';
import type { ReactNode } from 'react';

export const STANDARD_FIELD_LABELS: Record<string, string> = {
  externalId: '外部告警编号',
  title: '告警标题',
  severity: '风险等级',
  occurredAt: '发生时间',
  actor: '账号 / 操作人',
  assetRef: '资产 / 终端',
  alertType: '事件类型',
  'detail.phone': '敏感手机号',
  'detail.raw': '扩展详情',
  'detail.sourceFile': '来源文件',
};

export const PLAN_STATUS_FILTER_OPTIONS = [
  { value: 'suggested', label: '已推荐' },
  { value: 'review_required', label: '待复核' },
  { value: 'approved', label: '已批准' },
  { value: 'shadow_ready', label: '试运行准备' },
  { value: 'rejected', label: '已拒绝' },
];
export const PLAN_STATUS_FILTER_VALUES = new Set(PLAN_STATUS_FILTER_OPTIONS.map((option) => option.value));

const PLAN_STATUS_LABELS: Record<string, string> = {
  draft: '草稿',
  generated: '已生成',
  suggested: '已推荐',
  review: '待复核',
  review_required: '待复核',
  approved: '已批准',
  shadow_ready: '试运行准备',
  rejected: '已拒绝',
};

const PLAN_STATUS_COLORS: Record<string, string> = {
  draft: 'default',
  generated: 'processing',
  suggested: 'processing',
  review: 'warning',
  review_required: 'warning',
  approved: 'success',
  shadow_ready: 'cyan',
  rejected: 'error',
};

const SHADOW_VALIDATION_RESULT_LABELS: Record<string, string> = {
  passed: '校验通过',
  warning: '存在提醒',
  blocked: '存在阻断',
  failed: '未通过',
};

const SHADOW_VALIDATION_RESULT_COLORS: Record<string, string> = {
  passed: 'success',
  warning: 'warning',
  blocked: 'error',
  failed: 'error',
};

function firstDefined(record: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && value !== '') {
      return value;
    }
  }
  return undefined;
}

function toText(value: unknown): string {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (typeof value === 'object' && !Array.isArray(value)) {
    return toText(firstDefined(value as Record<string, unknown>, [
      'name',
      'fieldName',
      'field_name',
      'field',
      'standardField',
      'standard_field',
      'message',
      'description',
      'reason',
      'action',
    ]));
  }
  return '';
}

export function planStatusTag(value?: string) {
  const status = value || 'draft';
  return (
    <Tag color={PLAN_STATUS_COLORS[status] || 'default'} style={{ whiteSpace: 'normal', wordBreak: 'break-word' }}>
      {PLAN_STATUS_LABELS[status] || status}
    </Tag>
  );
}

export function shadowValidationResultTag(value?: string) {
  const result = value || 'unknown';
  return (
    <Tag color={SHADOW_VALIDATION_RESULT_COLORS[result] || 'default'} style={{ whiteSpace: 'normal', wordBreak: 'break-word' }}>
      {SHADOW_VALIDATION_RESULT_LABELS[result] || result}
    </Tag>
  );
}

export function renderWrappedTag(content: ReactNode, color?: string) {
  return (
    <Tag color={color} style={{ whiteSpace: 'normal', wordBreak: 'break-word', maxWidth: '100%' }}>
      {content}
    </Tag>
  );
}

export function renderTextTags(values: string[], emptyText = '-') {
  if (!values.length) {
    return <span>{emptyText}</span>;
  }
  return (
    <Space size={[4, 6]} wrap>
      {values.map((value) => <Tag key={value} style={{ whiteSpace: 'normal', wordBreak: 'break-word', maxWidth: '100%' }}>{value}</Tag>)}
    </Space>
  );
}

export function formatConfidence(value?: number) {
  if (value === undefined) {
    return '-';
  }
  const percent = value <= 1 ? value * 100 : value;
  return `${Math.round(percent)}%`;
}

export function formatUnknownValue(value: unknown): string {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  if (Array.isArray(value)) {
    return value.map(formatUnknownValue).filter(Boolean).join(' / ');
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return toText(value);
    }
  }
  return toText(value);
}
