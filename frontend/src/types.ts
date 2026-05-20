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
  data_source_name: string;
  table_name: string;
  category: string;
  confirmation_status: string;
  updated_at: string;
}

export interface SchemaFieldRow {
  id: number;
  field_name: string;
  field_type: string;
  nullable: boolean;
  sample_value?: string;
  description?: string;
}

export interface RuleRow {
  id: number;
  name: string;
  event_type: string;
  severity: Severity;
  expression: string;
  enabled: boolean;
  updated_at: string;
}

export interface AlertRow {
  id: number;
  title: string;
  severity: Severity;
  status: string;
  subject_type: string;
  subject_ref: string;
  source_system?: string;
  external_id?: string;
  alert_type?: string;
  occurred_at?: string;
  actor?: string;
  asset_ref?: string;
  policy_name?: string;
  detail_json?: Record<string, unknown> | string;
  created_at: string;
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
  endpoint_masked: string;
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
  created_at: string;
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
