import type { Severity } from '../types';

export type StatusTone = 'success' | 'processing' | 'warning' | 'error' | 'default';

export interface StatusDisplay {
  label: string;
  color: StatusTone;
}

export function formatBusinessTime(value?: string | number | null) {
  if (value === undefined || value === null || value === '') {
    return '-';
  }

  const normalizedValue = normalizeTimeValue(value);
  if (normalizedValue === null) {
    return '-';
  }

  const date = new Date(normalizedValue);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }

  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function normalizeTimeValue(value: string | number) {
  if (typeof value === 'number') {
    return value < 100000000000 ? value * 1000 : value;
  }

  const text = value.trim();
  if (!text) {
    return null;
  }
  if (/^\d{10,13}$/.test(text)) {
    const numeric = Number(text);
    return numeric < 100000000000 ? numeric * 1000 : numeric;
  }
  return text;
}

export function getProviderLabel(providerKey?: string, enabled = true) {
  const label = {
    auto: '默认智能分析',
    'local-openai-compatible': '本地模型',
    'cloud-openai-compatible': '企业云模型',
    'local-ollama-compatible': '本地 Ollama 模型',
    'fallback-template': '安全模板',
  }[providerKey || ''] ?? '其他分析模型';

  return enabled ? label : `${label}（未配置）`;
}

export function getProviderHelp(providerKey?: string) {
  return {
    auto: '系统自动选择可用分析能力。',
    'local-openai-compatible': '使用本地部署模型生成运营建议。',
    'cloud-openai-compatible': '使用企业云模型生成运营建议。',
    'local-ollama-compatible': '使用本地 Ollama 模型生成运营建议。',
    'fallback-template': '模型不可用时使用安全模板生成，不包含原始敏感数据。',
  }[providerKey || ''] ?? '未识别的模型配置，已按其他分析模型展示。';
}

export function getSourceLabel(source?: string) {
  return {
    llm: '模型生成',
    'fallback-template': '安全模板生成',
  }[source || ''] ?? '系统生成';
}

export function getAiRunStatus(status?: string): StatusDisplay {
  const statuses: Record<string, StatusDisplay> = {
    passed: { label: '已生成', color: 'success' },
    warning: { label: '降级生成', color: 'warning' },
    failed: { label: '生成失败', color: 'error' },
    running: { label: '生成中', color: 'processing' },
  };
  return statuses[status || ''] ?? { label: '状态待确认', color: 'default' };
}

export function getPeriodLabel(period?: string) {
  return {
    today: '今日',
    last_1_day: '今日',
    last_24h: '最近 24 小时',
    last_7_days: '最近 7 天',
    last_30_days: '最近 30 天',
    custom: '自定义周期',
  }[period || ''] ?? '自定义周期';
}

export function getThemeLabel(theme?: string) {
  return {
    security_overview: '安全态势概览',
    high_risk_alerts: '高危告警',
    sync_health: '同步链路健康',
    sync_pipeline_health: '同步链路健康',
    notification_readiness: '通知准备度',
    rule_effectiveness: '规则有效性',
  }[theme || ''] ?? '综合运营分析';
}

export function getSeverityLabel(value?: Severity | string) {
  return {
    critical: '严重',
    high: '高危',
    medium: '中危',
    low: '低危',
    info: '提示',
  }[value || ''] ?? '未分级';
}

export function getSeverityColor(value?: Severity | string): StatusTone {
  const colors: Record<string, StatusTone> = {
    critical: 'error',
    high: 'error',
    medium: 'warning',
    low: 'processing',
    info: 'default',
  };
  return colors[value || ''] ?? 'default';
}

export function getAlertStatus(status?: string): StatusDisplay {
  const statuses: Record<string, StatusDisplay> = {
    open: { label: '开放', color: 'warning' },
    acknowledged: { label: '已确认', color: 'processing' },
    processing: { label: '处理中', color: 'processing' },
    resolved: { label: '已处理', color: 'success' },
    closed: { label: '已关闭', color: 'default' },
  };
  return statuses[status || ''] ?? { label: '状态待确认', color: 'default' };
}

export function getDeliveryStatus(status?: string): StatusDisplay {
  const statuses: Record<string, StatusDisplay> = {
    success: { label: '发送成功', color: 'success' },
    failed: { label: '发送失败', color: 'error' },
    error: { label: '发送异常', color: 'error' },
    pending: { label: '等待发送', color: 'processing' },
    running: { label: '发送中', color: 'processing' },
  };
  return statuses[status || ''] ?? { label: '状态待确认', color: 'default' };
}

export function getRuleDecisionLabel(decision?: string) {
  return {
    matched: '已命中',
    not_matched: '未命中',
    skipped: '已跳过',
    error: '评估异常',
  }[decision || ''] ?? '结果待确认';
}

export function getRuleDecisionColor(decision?: string): StatusTone {
  const colors: Record<string, StatusTone> = {
    matched: 'error',
    not_matched: 'default',
    skipped: 'warning',
    error: 'error',
  };
  return colors[decision || ''] ?? 'default';
}

export function getEventTypeLabel(value?: string) {
  return {
    file_operation: '文件操作',
    file_transfer: '文件外发',
    device_operation: '外设操作',
    data_access: '数据访问',
    account_activity: '账号行为',
    login: '登录行为',
    generic: '未分类事件',
    standard: '标准告警',
  }[value || ''] ?? '其他事件';
}

export function getRuleScenarioLabel(row: { name?: string; event_type?: string; eventType?: string; expression?: string }) {
  const text = `${row.name || ''} ${row.event_type || row.eventType || ''} ${row.expression || ''}`.toLowerCase();
  if (/file_transfer|transfer|mail|attachment|外发|附件/.test(text)) {
    return '检测邮件附件或文件外发行为';
  }
  if (/device|usb|removable|移动|外设/.test(text)) {
    return '检测移动存储或外设操作风险';
  }
  if (/data_access|access|query|download|敏感/.test(text)) {
    return '检测敏感数据访问频率异常';
  }
  if (/login|account|账号|登录/.test(text)) {
    return '检测异常登录和账号行为';
  }
  if (/file|文件/.test(text)) {
    return '检测文件操作风险';
  }
  return '检测安全事件是否达到告警条件';
}

export function getDataSourceTypeLabel(value?: string) {
  return {
    sqlserver: 'SQL Server',
    mssql: 'SQL Server',
    mysql: 'MySQL',
    postgresql: 'PostgreSQL',
    oracle: 'Oracle',
    http_api: 'HTTP API',
    webhook: 'Webhook',
    file_import: '文件导入',
    security_platform: '安全平台',
    database: '数据库',
  }[value || ''] ?? '其他接入';
}

export function getConnectionKindLabel(value?: string) {
  return {
    database: '数据库接入',
    api: '接口接入',
    webhook: '实时接收',
    file: '文件导入',
    security_platform: '安全平台接入',
  }[value || ''] ?? '其他接入';
}

export function getCollectionRunTypeLabel(value?: string) {
  return {
    manual: '人工触发',
    scheduled: '定时执行',
    realtime: '实时接收',
    backfill: '补采',
  }[value || ''] ?? '系统执行';
}

export function getRiskKeyLabel(key?: string) {
  return {
    missing_occurred_at: '缺少发生时间字段',
    coverage_unknown: '字段覆盖率未知',
    shadow_run_stale_after_plan_edit: '方案已调整，需要重新试运行',
    shadow_run_plan_fingerprint_missing: '缺少试运行校验记录',
    shadow_run_plan_fingerprint_invalid: '试运行校验记录不匹配',
  }[key || ''] ?? (key ? '需要运营复核' : '-');
}

export function getPlanRuntimeStatus(status?: string): StatusDisplay {
  const statuses: Record<string, StatusDisplay> = {
    active: { label: '已启用', color: 'success' },
    inactive: { label: '未启用', color: 'default' },
    deactivated: { label: '已停用', color: 'default' },
    enabled: { label: '已启用', color: 'processing' },
    paused: { label: '已暂停', color: 'default' },
    passed: { label: '已通过', color: 'success' },
    warning: { label: '存在提醒', color: 'warning' },
    blocked: { label: '存在阻断', color: 'error' },
    failed: { label: '未通过', color: 'error' },
    skipped: { label: '已跳过', color: 'default' },
  };
  return statuses[status || ''] ?? { label: '状态待确认', color: 'default' };
}

export function getReportStatus(status?: string): StatusDisplay {
  if (['completed', 'success', 'done'].includes(status || '')) {
    return { label: '可下载', color: 'success' };
  }
  if (['running', 'processing'].includes(status || '')) {
    return { label: '正在生成', color: 'processing' };
  }
  if (['failed', 'error'].includes(status || '')) {
    return { label: '生成失败，请重试', color: 'error' };
  }
  return { label: '尚未生成', color: 'default' };
}

export function getChannelTypeLabel(value?: string) {
  return {
    webhook: 'Webhook',
    wecom: '企业微信',
    feishu: '飞书',
  }[value || ''] ?? '其他通道';
}

export function getSecretStorageStatusLabel(status?: string): StatusDisplay {
  const statuses: Record<string, StatusDisplay> = {
    encrypted: { label: '已加密', color: 'success' },
    legacy_plaintext: { label: '需重新配置', color: 'warning' },
    missing: { label: '未配置', color: 'default' },
  };
  return statuses[status || ''] ?? { label: '状态待确认', color: 'default' };
}

export function getBackfillRunStatus(status?: string): StatusDisplay {
  const statuses: Record<string, StatusDisplay> = {
    completed: { label: '已完成', color: 'success' },
    completed_with_failures: { label: '部分完成', color: 'warning' },
    failed: { label: '执行失败', color: 'error' },
    running: { label: '执行中', color: 'processing' },
    migrated: { label: '已迁移', color: 'success' },
    skipped: { label: '已跳过', color: 'default' },
  };
  return statuses[status || ''] ?? { label: '状态待确认', color: 'default' };
}

export function getFailureReasonLabel(reason?: string | null) {
  return {
    not_found: '记录不存在',
    already_encrypted: '已完成加密',
    not_legacy_plaintext: '无需迁移',
    endpoint_missing: '通道地址缺失',
    endpoint_invalid: '通道地址无效',
    unsupported_channel_type: '通道类型暂不支持',
    notification_secret_key_missing: '密钥配置缺失',
    notification_secret_key_invalid: '密钥配置无效',
    notification_secret_store_failed: '密钥保存失败',
    unexpected_error: '系统异常',
  }[reason || ''] ?? (reason ? '失败原因待确认' : '-');
}

export function technicalValue(value: unknown) {
  if (value === undefined || value === null || value === '') {
    return '-';
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '-';
  }
}
