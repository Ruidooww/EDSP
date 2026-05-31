export type Severity = 'critical' | 'high' | 'medium' | 'low' | 'info';

export interface DataSourceRow {
  id: number;
  name: string;
  source_type: string;
  connection_kind: string;
  status: string;
  description?: string;
  enabled: boolean;
  updated_at: string;
}

export interface SchemaTableRow {
  id: number;
  data_source_id?: number;
  scan_run_id?: number;
  data_source_name: string;
  schema_name?: string;
  table_name: string;
  table_type?: string;
  category: string;
  row_count?: number;
  confirmation_status: string;
  lifecycle_status?: string;
  updated_at: string;
}

export interface SchemaFieldRow {
  id: number;
  scan_run_id?: number;
  field_name: string;
  field_type: string;
  nullable: boolean;
  sample_value?: string;
  description?: string;
  ordinal_position?: number;
  semantic_type?: string;
  confidence?: number;
  is_candidate_key?: boolean;
  is_time_candidate?: boolean;
  lifecycle_status?: string;
}

export interface RuleRow {
  id: number;
  name: string;
  event_type: string;
  eventType?: string;
  severity: Severity;
  expression: string;
  enabled: boolean;
  updated_at: string;
  created_at?: string;
  createdAt?: string | number;
  updatedAt?: string | number;
}

export type RuleDecision = 'matched' | 'not_matched' | 'skipped' | 'error';

export interface RuleRequest {
  name: string;
  eventType: string;
  severity: Severity;
  expression: string;
  enabled: boolean;
}

export interface RuleEnabledRequest {
  enabled: boolean;
}

export interface RuleEvaluationRunRequest {
  standardEventId: number;
  ruleId?: number;
  operatorName?: string;
}

export interface RuleEvaluationRunResult {
  standardEventId: number;
  ruleId?: number;
  evaluatedCount: number;
  matchedCount?: number;
  notMatchedCount?: number;
  skippedCount?: number;
  errorCount?: number;
  decisions?: RuleEvaluationDecisionRow[];
}

export interface RuleEvaluationDecisionRow {
  id?: number;
  standardEventId?: number;
  standard_event_id?: number;
  ruleId?: number;
  rule_id?: number;
  ruleName?: string;
  rule_name?: string;
  decision: RuleDecision | string;
  severity?: Severity;
  riskScore?: number;
  risk_score?: number;
  reason?: string;
  detail?: Record<string, unknown> | string;
  detailJson?: Record<string, unknown> | string;
  detail_json?: Record<string, unknown> | string;
  createdAt?: string | number;
  created_at?: string | number;
}

export interface AlertRow {
  id: number;
  title: string;
  severity: Severity;
  status: string;
  assigned_to?: string;
  assignedTo?: string;
  subject_type?: string;
  subject_ref?: string;
  subjectType?: string;
  subjectRef?: string;
  source_system?: string;
  sourceSystem?: string;
  external_id?: string;
  externalId?: string;
  alert_type?: string;
  alertType?: string;
  occurred_at?: string;
  occurredAt?: string | number;
  actor?: string;
  asset_ref?: string;
  assetRef?: string;
  policy_name?: string;
  policyName?: string;
  standard_event_id?: number;
  standardEventId?: number;
  rule_id?: number;
  ruleId?: number;
  rule_name?: string;
  ruleName?: string;
  alert_decision_id?: number;
  alertDecisionId?: number;
  decisionId?: number;
  detail?: Record<string, unknown> | string;
  detail_json?: Record<string, unknown> | string;
  created_at: string;
  createdAt?: string | number;
  updated_at?: string | number;
  updatedAt?: string | number;
}

export type AlertLifecycleAction = 'acknowledge' | 'assign' | 'close';

export interface AlertLifecycleRequest {
  operatorName: string;
  assignee?: string;
  note?: string;
}

export interface AlertTimelineRow {
  id: number;
  alert_id?: number;
  alertId?: number;
  action?: string;
  eventType?: string;
  event_type?: string;
  operator_name?: string;
  operatorName?: string;
  assignee?: string;
  assigned_to?: string;
  assignedTo?: string;
  note?: string;
  detail?: Record<string, unknown> | string;
  detail_json?: Record<string, unknown> | string;
  created_at?: string | number;
  createdAt?: string | number;
}

export interface AlertGenerationRunRequest {
  decisionId: number;
}

export interface AlertGenerationRunResult extends AlertRow {
  action: 'created' | 'existing';
  decisionId?: number;
}

export interface AlertNoteRow {
  id: number;
  alert_id: number;
  operator_name: string;
  note: string;
  created_at: string;
}

export interface ReportJobRow {
  id: number;
  report_type: string;
  title: string;
  status: string;
  file_path: string;
  updated_at?: string;
  created_at: string;
}

export interface AuditLogRow {
  id: number;
  actor?: string;
  action: string;
  target_type?: string;
  target_id?: string;
  detail_json?: Record<string, unknown> | string;
  created_at: string | number;
}

export interface UserProfile {
  username: string;
  displayName: string;
  roles: string[];
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface NotificationChannelRow {
  id: number;
  name: string;
  channel_type: string;
  channelType?: string;
  endpoint_masked: string;
  endpointMasked?: string;
  secret_storage_status?: string;
  secretStorageStatus?: string;
  description?: string;
  enabled: boolean;
  status: string;
  last_test_status?: string;
  last_test_message?: string;
  last_test_at?: string;
  updated_at: string;
}

export interface NotificationDeliveryRow {
  id: number;
  channel_id?: number;
  channel_name?: string;
  channel_type?: string;
  alert_id?: number;
  alert_title?: string;
  title: string;
  severity?: Severity;
  status: string;
  response_code?: number;
  response_body?: string;
  payload_json?: Record<string, unknown> | string;
  failure_type?: string | null;
  failure_reason?: string | null;
  retryable?: boolean;
  retry_of_delivery_id?: number | null;
  retry_count?: number;
  created_at: string;
}

export interface NotificationSecretBackfillDryRunSummary {
  totalChannels: number;
  legacyPlaintext: number;
  migrationEligible: number;
  blocked: number;
  encrypted: number;
  missing: number;
  unsupportedStatus?: number;
}

export interface NotificationSecretBackfillDryRunItem {
  id: number;
  name: string;
  channelType: string;
  enabled: boolean;
  secretStorageStatus: string;
  endpointMasked: string;
  dryRunStatus: string;
  blockReason?: string | null;
  migrationEligible: boolean;
  updatedAt?: string | number;
}

export interface NotificationSecretBackfillDryRunResult {
  summary: NotificationSecretBackfillDryRunSummary;
  blockReasons: Record<string, number>;
  limit: number;
  truncated: boolean;
  items: NotificationSecretBackfillDryRunItem[];
}

export interface NotificationSecretBackfillRunItem {
  id: number;
  run_id?: number;
  channel_id: number;
  channel_type?: string | null;
  before_secret_storage_status?: string | null;
  after_secret_storage_status?: string | null;
  endpoint_masked?: string | null;
  item_status: string;
  failure_reason?: string | null;
  created_at?: string | number;
  updated_at?: string | number;
}

export interface NotificationSecretBackfillRun {
  id: number;
  mode: string;
  status: string;
  confirmation_accepted?: boolean;
  requested_by?: string | null;
  requested_at?: string | number;
  started_at?: string | number;
  completed_at?: string | number;
  total_requested: number;
  eligible_count: number;
  migrated_count: number;
  skipped_count: number;
  failed_count: number;
  failure_reason?: string | null;
  created_at?: string | number;
  updated_at?: string | number;
  items?: NotificationSecretBackfillRunItem[];
}

export interface NotificationSecretBackfillRunListResult {
  limit: number;
  items: NotificationSecretBackfillRun[];
}

export interface NotificationAlertSendRequest {
  alertId: number;
  channelId: number;
}

export interface NotificationAlertSendResult {
  alertId?: number;
  channelId?: number;
  channelName?: string;
  status?: string;
  message?: string;
  responseCode?: number;
  responseBody?: string;
  deliveryId?: number;
  total?: number;
  success?: number;
  failed?: number;
  results?: Array<{
    channelId?: number;
    channelName?: string;
    status?: string;
    message?: string;
    responseCode?: number;
    responseBody?: string;
  }>;
}

export interface OverviewTrendPoint {
  date: string;
  label: string;
  value: number;
}

export interface OverviewDataSourceRow extends DataSourceRow {
  field_count: number;
  mapping_count: number;
  mapped_percent: number;
}

export interface OverviewAlertRow extends AlertRow {
  updated_at?: string;
}

export interface OverviewSecurityOperations {
  totalAlerts: number;
  openAlerts: number;
  acknowledgedAlerts: number;
  closedAlerts: number;
  highRiskAlerts: number;
  todayAlerts: number;
}

export interface OverviewNotificationDelivery {
  todayTotal: number;
  todaySuccess: number;
  todayFailed: number;
  todaySuccessRate: number;
  retryableFailed: number;
  byFailureType: Record<string, number>;
  recentFailed: OverviewNotificationDeliveryRow[];
}

export interface OverviewNotificationDeliveryRow {
  id: number;
  channel_id?: number;
  channel_name?: string;
  channel_type?: string;
  alert_id?: number;
  alert_title?: string;
  title: string;
  status: string;
  response_code?: number;
  failure_type?: string | null;
  failure_reason?: string | null;
  retryable: boolean;
  retry_count: number;
  retry_of_delivery_id?: number | null;
  created_at: string | number;
}

export interface OverviewNotificationChannels {
  total: number;
  enabled: number;
  disabled: number;
  byType: Record<string, number>;
}

export interface OverviewLifecycleEventRow {
  id: number;
  alert_id: number;
  alertId?: number;
  alert_title: string;
  alertTitle?: string;
  event_type: string;
  eventType?: string;
  operator_name: string;
  operatorName?: string;
  assignee?: string;
  created_at: string | number;
  createdAt?: string | number;
}

export interface OverviewData {
  requestTime: string;
  dataSources: {
    total: number;
    healthy: number;
    abnormal: number;
    unchecked: number;
    enabled: number;
    disabled: number;
    healthRate: number;
  };
  schema: {
    tables: number;
    fields: number;
    mappings: number;
    confirmedTables: number;
    mappedRate: number;
  };
  rules: {
    total: number;
    enabled: number;
    disabled: number;
    enabledRate: number;
  };
  alerts: {
    open: number;
    today: number;
    yesterday: number;
    delta: number;
    bySeverity: Record<string, number>;
    byStatus: Record<string, number>;
    trend: OverviewTrendPoint[];
    recent: OverviewAlertRow[];
  };
  reports: {
    total: number;
    completed: number;
    running: number;
    failed: number;
    pending: number;
    byStatus: Record<string, number>;
  };
  recentDataSources: OverviewDataSourceRow[];
  securityOperations: OverviewSecurityOperations;
  notificationDelivery: OverviewNotificationDelivery;
  notificationChannels: OverviewNotificationChannels;
  recentLifecycleEvents: OverviewLifecycleEventRow[];
}

export interface CollectionTaskRow {
  id: number;
  name: string;
  task_type: string;
  schedule_mode: string;
  interval_seconds: number;
  status: string;
  enabled: boolean;
  last_run_at?: string;
  next_run_at?: string;
  data_source_name: string;
  source_type: string;
  adapter_name?: string;
  created_at: string;
  updated_at: string;
}

export interface IngestionRunRow {
  id: number;
  task_id?: number;
  task_name?: string;
  data_source_name?: string;
  run_type: string;
  status: string;
  started_at: string;
  finished_at?: string;
  read_count: number;
  success_count: number;
  failed_count: number;
  skipped_count: number;
  error_message?: string;
}

export interface RawEventRow {
  id: number;
  data_source_id?: number;
  data_source_name?: string;
  task_id?: number;
  run_id?: number;
  source_system?: string;
  external_id?: string;
  event_type?: string;
  occurred_at?: string;
  received_at: string;
  status: string;
  standard_event_id?: number;
}

export interface StandardEventRow {
  id: number;
  data_source_id?: number;
  data_source_name?: string;
  source_system: string;
  external_id?: string;
  event_type: string;
  occurred_at?: string;
  actor?: string;
  asset_ref?: string;
  subject_type?: string;
  subject_ref?: string;
  action?: string;
  result?: string;
  severity: Severity;
  risk_score: number;
  created_at: string;
  updated_at: string;
}

export interface SchemaScanRunRow {
  id: number;
  data_source_id: number;
  data_source_name: string;
  scan_type: string;
  status: string;
  started_at: string;
  finished_at?: string;
  total_databases: number;
  scanned_databases: number;
  failed_databases: number;
  total_tables: number;
  scanned_tables: number;
  failed_tables: number;
  total_fields: number;
  scanned_fields: number;
  error_message?: string;
}

export interface SchemaChangeEventRow {
  id: number;
  data_source_id: number;
  data_source_name: string;
  scan_run_id?: number;
  schema_table_id?: number;
  schema_field_id?: number;
  object_type: string;
  change_type: string;
  object_name: string;
  severity: Severity;
  status: string;
  reason?: string;
  created_at: string | number;
  updated_at?: string | number;
}

export interface IngestionPlanFieldMapping {
  sourceField?: string;
  source_field?: string;
  standardField?: string;
  standard_field?: string;
  confidence?: number | string;
  transformRule?: string;
  transform_rule?: string;
  transformRulePayload?: TransformRulePayload | Record<string, unknown>;
  transform_rule_payload?: TransformRulePayload | Record<string, unknown>;
  reason?: string;
  [key: string]: unknown;
}

export interface TransformRulePayload {
  type?: string;
  values?: Record<string, string>;
  onMissing?: 'keepOriginal' | 'useDefault';
  defaultValue?: string;
  [key: string]: unknown;
}

export interface IngestionPlanMappingRuleUpdateRequest {
  sourceField: string;
  standardField: string;
  transformRule?: string | null;
  transformRulePayload?: TransformRulePayload | Record<string, unknown> | null;
}

export interface IngestionPlanFieldEvidence {
  sourceField?: string;
  source_field?: string;
  fieldName?: string;
  field_name?: string;
  standardField?: string;
  standard_field?: string;
  reason?: string;
  description?: string;
  message?: string;
  explanation?: string;
}

export interface IngestionPlanSignalEvidence {
  signal: string;
  sourceFields: Array<string | number> | string;
  source_fields?: Array<string | number> | string;
  source: string;
}

export interface IngestionPlanTemplateMatch {
  templateKey?: string;
  template_key?: string;
  templateName?: string;
  template_name?: string;
  confidence?: number | string;
  matchedBy?: string;
  matched_by?: string;
  mainPlanCandidate?: boolean;
  main_plan_candidate?: boolean;
  matchedSignals?: Array<string | number>;
  matched_signals?: Array<string | number>;
  missingSignals?: Array<string | number>;
  missing_signals?: Array<string | number>;
  signalEvidence?: IngestionPlanSignalEvidence[] | Record<string, IngestionPlanSignalEvidence>;
  signal_evidence?: IngestionPlanSignalEvidence[] | Record<string, IngestionPlanSignalEvidence>;
  reason?: string;
  [key: string]: unknown;
}

export interface IngestionPlanDedupStrategy {
  type?: string;
  fields?: Array<string | number>;
  sourceFields?: Array<string | number>;
  source_fields?: Array<string | number>;
  fallback?: string;
  stable?: boolean;
  rule?: string;
  window?: string;
  description?: string;
}

export interface IngestionPlanJson {
  candidateTable?: string;
  candidate_table?: string;
  tableName?: string;
  table_name?: string;
  templateType?: string;
  template_type?: string;
  overallConfidence?: number | string;
  overall_confidence?: number | string;
  confidence?: number | string;
  templateConfidence?: number | string;
  template_confidence?: number | string;
  coverageConfidence?: number | string;
  coverage_confidence?: number | string;
  mappingCompleteness?: number | string;
  mapping_completeness?: number | string;
  fieldMappings?: IngestionPlanFieldMapping[] | Record<string, string>;
  field_mappings?: IngestionPlanFieldMapping[] | Record<string, string>;
  fieldMappingDetails?: IngestionPlanFieldMapping[];
  field_mapping_details?: IngestionPlanFieldMapping[];
  mappings?: IngestionPlanFieldMapping[];
  dedupStrategy?: IngestionPlanDedupStrategy;
  dedup_strategy?: IngestionPlanDedupStrategy;
  requiredFieldsMissing?: unknown[];
  required_fields_missing?: unknown[];
  missingFields?: unknown[];
  missing_fields?: unknown[];
  risks?: unknown[];
  riskTips?: unknown[];
  risk_tips?: unknown[];
  recommendedActions?: unknown[];
  recommendedAction?: unknown;
  recommended_action?: unknown;
  recommended_actions?: unknown[];
  actions?: unknown[];
  reason?: string;
  reasons?: unknown[];
  explanation?: string;
  fieldEvidence?: Record<string, IngestionPlanFieldEvidence> | IngestionPlanFieldEvidence[];
  field_evidence?: Record<string, IngestionPlanFieldEvidence> | IngestionPlanFieldEvidence[];
  templateMatch?: IngestionPlanTemplateMatch;
  template_match?: IngestionPlanTemplateMatch;
  generationVersion?: string;
  generation_version?: string;
  generatedAt?: string | number;
  generated_at?: string | number;
  [key: string]: unknown;
}

export interface IngestionPlanRow {
  id: number;
  data_source_id?: number;
  dataSourceId?: number;
  data_source_name?: string;
  dataSourceName?: string;
  scan_run_id?: number;
  scanRunId?: number;
  name?: string;
  status: string;
  plan_json?: IngestionPlanJson | string;
  planJson?: IngestionPlanJson | string;
  created_at?: string | number;
  createdAt?: string | number;
  updated_at?: string | number;
  updatedAt?: string | number;
  generated_at?: string | number;
  generatedAt?: string | number;
  generation_version?: string;
  generationVersion?: string;
  candidate_table?: string;
  candidateTable?: string;
  template_type?: string;
  templateType?: string;
  overall_confidence?: number | string;
  overallConfidence?: number | string;
  template_confidence?: number | string;
  templateConfidence?: number | string;
  coverage_confidence?: number | string;
  coverageConfidence?: number | string;
  mapping_completeness?: number | string;
  mappingCompleteness?: number | string;
}

export interface IngestionPlanShadowValidationCheck {
  code: string;
  result: 'passed' | 'warning' | 'failed' | string;
  message?: string;
  blockers?: string[];
  details?: unknown;
}

export interface IngestionPlanShadowValidationReport {
  planId: number;
  dataSourceId?: number;
  dataSourceName?: string;
  planStatus: string;
  result: 'passed' | 'warning' | 'blocked' | string;
  statusRecommendation?: string;
  checkedAt?: string | number;
  sampleLimit?: number;
  mainTable?: string;
  templateKey?: string;
  mappedFieldCount?: number;
  blockers?: string[];
  warnings?: string[];
  standardEventPreview?: Record<string, unknown>;
  checks: IngestionPlanShadowValidationCheck[];
}

export interface IngestionPlanShadowRunSummary {
  status?: string;
  sampleLimit?: number;
  readCount?: number;
  successCount?: number;
  failedCount?: number;
  duplicateCount?: number;
  missingRequiredCount?: number;
}

export interface IngestionPlanShadowRunSample {
  sourcePreview?: Record<string, unknown>;
  standardEventPreview?: Record<string, unknown>;
  errors?: string[];
  warnings?: string[];
}

export interface IngestionPlanShadowRunReport {
  planId?: number;
  dataSourceId?: number;
  status?: 'passed' | 'warning' | 'blocked' | 'failed' | string;
  summary?: IngestionPlanShadowRunSummary;
  checks?: IngestionPlanShadowValidationCheck[];
  blockers?: string[];
  warnings?: string[];
  samples?: IngestionPlanShadowRunSample[];
  errorsByType?: Record<string, number>;
  previewPolicy?: Record<string, unknown>;
}

export interface IngestionPlanShadowRunRow {
  id: number;
  ingestionPlanId: number;
  ingestion_plan_id?: number;
  dataSourceId?: number;
  data_source_id?: number;
  status: 'passed' | 'warning' | 'blocked' | 'failed' | string;
  sampleLimit: number;
  sample_limit?: number;
  readCount: number;
  read_count?: number;
  successCount: number;
  success_count?: number;
  failedCount: number;
  failed_count?: number;
  duplicateCount: number;
  duplicate_count?: number;
  missingRequiredCount: number;
  missing_required_count?: number;
  startedAt?: string | number;
  started_at?: string | number;
  finishedAt?: string | number;
  finished_at?: string | number;
  durationMs?: number;
  duration_ms?: number;
  errorMessage?: string;
  error_message?: string;
  report?: IngestionPlanShadowRunReport;
  createdAt?: string | number;
  created_at?: string | number;
  updatedAt?: string | number;
  updated_at?: string | number;
}

export interface IngestionPlanActivationRow {
  id: number;
  ingestionPlanId?: number;
  ingestion_plan_id?: number;
  dataSourceId?: number;
  data_source_id?: number;
  shadowRunId?: number;
  shadow_run_id?: number;
  status: 'active' | 'deactivated' | string;
  activatedBy?: string;
  activated_by?: string;
  activationReason?: string;
  activation_reason?: string;
  operatorName?: string;
  operator_name?: string;
  reason?: string;
  activatedAt?: string | number;
  activated_at?: string | number;
  deactivatedBy?: string;
  deactivated_by?: string;
  deactivationReason?: string;
  deactivation_reason?: string;
  deactivatedAt?: string | number;
  deactivated_at?: string | number;
  config?: Record<string, unknown>;
  config_json?: Record<string, unknown> | string;
  createdAt?: string | number;
  created_at?: string | number;
  updatedAt?: string | number;
  updated_at?: string | number;
}

export interface RuleDecisionAutoSummary {
  mode?: 'new_standard_events_only' | string;
  status?: 'passed' | 'warning' | 'skipped' | string;
  evaluatedStandardCount?: number;
  decisionCount?: number;
  matchedCount?: number;
  notMatchedCount?: number;
  skippedCount?: number;
  errorCount?: number;
  failedStandardCount?: number;
  errorsByType?: Record<string, number>;
  errorMessage?: string;
}

export interface IngestionPlanSyncRunReport {
  mode?: string;
  boundary?: string;
  planId?: number;
  activationId?: number;
  scheduleId?: number;
  triggerType?: 'manual' | 'scheduled' | string;
  ingestionRunId?: number;
  status?: 'passed' | 'warning' | 'blocked' | 'failed' | string;
  sampleLimit?: number;
  readCount?: number;
  successCount?: number;
  failedCount?: number;
  duplicateCount?: number;
  rawCount?: number;
  standardCount?: number;
  warnings?: string[];
  errorsByType?: Record<string, number>;
  blockers?: string[];
  errorMessage?: string;
  ruleDecisionAuto?: RuleDecisionAutoSummary;
}

export interface IngestionPlanSyncRunRow {
  id: number;
  ingestionPlanId?: number;
  ingestion_plan_id?: number;
  activationId?: number;
  activation_id?: number;
  dataSourceId?: number;
  data_source_id?: number;
  shadowRunId?: number;
  shadow_run_id?: number;
  ingestionRunId?: number;
  ingestion_run_id?: number;
  scheduleId?: number;
  schedule_id?: number;
  triggerType?: 'manual' | 'scheduled' | string;
  trigger_type?: 'manual' | 'scheduled' | string;
  status: 'passed' | 'warning' | 'blocked' | 'failed' | string;
  sampleLimit?: number;
  sample_limit?: number;
  readCount?: number;
  read_count?: number;
  successCount?: number;
  success_count?: number;
  failedCount?: number;
  failed_count?: number;
  duplicateCount?: number;
  duplicate_count?: number;
  rawCount?: number;
  raw_count?: number;
  standardCount?: number;
  standard_count?: number;
  startedAt?: string | number;
  started_at?: string | number;
  finishedAt?: string | number;
  finished_at?: string | number;
  durationMs?: number;
  duration_ms?: number;
  errorMessage?: string;
  error_message?: string;
  report?: IngestionPlanSyncRunReport;
  createdAt?: string | number;
  created_at?: string | number;
  updatedAt?: string | number;
  updated_at?: string | number;
}

export interface IngestionPlanSyncScheduleRow {
  id: number;
  ingestionPlanId?: number;
  ingestion_plan_id?: number;
  activationId?: number;
  activation_id?: number;
  dataSourceId?: number;
  data_source_id?: number;
  status: 'enabled' | 'paused' | string;
  intervalSeconds?: number;
  interval_seconds?: number;
  sampleLimit?: number;
  sample_limit?: number;
  nextRunAt?: string | number;
  next_run_at?: string | number;
  lastRunAt?: string | number;
  last_run_at?: string | number;
  lastSyncRunId?: number;
  last_sync_run_id?: number;
  lastStatus?: string;
  last_status?: string;
  lastErrorMessage?: string;
  last_error_message?: string;
  consecutiveFailures?: number;
  consecutive_failures?: number;
  lockedAt?: string | number;
  locked_at?: string | number;
  lockOwner?: string;
  lock_owner?: string;
  createdBy?: string;
  created_by?: string;
  updatedBy?: string;
  updated_by?: string;
  config?: Record<string, unknown>;
  config_json?: Record<string, unknown> | string;
  createdAt?: string | number;
  created_at?: string | number;
  updatedAt?: string | number;
  updated_at?: string | number;
}
