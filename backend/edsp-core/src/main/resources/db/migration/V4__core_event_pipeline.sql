create table if not exists collector_adapters (
    id bigserial primary key,
    name varchar(160) not null,
    adapter_key varchar(120) not null unique,
    source_type varchar(64) not null,
    connection_kind varchar(64) not null,
    description text,
    status varchar(32) not null default 'available',
    config_schema_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists collection_tasks (
    id bigserial primary key,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    adapter_id bigint references collector_adapters(id) on delete set null,
    name varchar(180) not null,
    task_type varchar(64) not null default 'pull',
    schedule_mode varchar(64) not null default 'manual',
    interval_seconds integer not null default 300,
    status varchar(32) not null default 'draft',
    enabled boolean not null default true,
    config_json jsonb not null default '{}'::jsonb,
    last_run_at timestamptz,
    next_run_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists ingestion_runs (
    id bigserial primary key,
    task_id bigint references collection_tasks(id) on delete set null,
    data_source_id bigint references data_sources(id) on delete set null,
    run_type varchar(64) not null default 'manual',
    status varchar(32) not null default 'running',
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    cursor_before text,
    cursor_after text,
    read_count bigint not null default 0,
    success_count bigint not null default 0,
    failed_count bigint not null default 0,
    skipped_count bigint not null default 0,
    error_message text,
    quality_report_json jsonb not null default '{}'::jsonb
);

create table if not exists ingestion_cursors (
    id bigserial primary key,
    task_id bigint not null references collection_tasks(id) on delete cascade,
    cursor_key varchar(160) not null default 'default',
    cursor_value text,
    updated_at timestamptz not null default now(),
    unique(task_id, cursor_key)
);

create table if not exists raw_events (
    id bigserial primary key,
    data_source_id bigint references data_sources(id) on delete set null,
    task_id bigint references collection_tasks(id) on delete set null,
    run_id bigint references ingestion_runs(id) on delete set null,
    source_system varchar(120),
    external_id varchar(200),
    event_type varchar(120),
    occurred_at timestamptz,
    received_at timestamptz not null default now(),
    payload_json jsonb not null default '{}'::jsonb,
    payload_hash varchar(128),
    status varchar(32) not null default 'received',
    standard_event_id bigint
);

create table if not exists raw_logs (
    id bigserial primary key,
    data_source_id bigint references data_sources(id) on delete set null,
    task_id bigint references collection_tasks(id) on delete set null,
    run_id bigint references ingestion_runs(id) on delete set null,
    log_type varchar(120),
    occurred_at timestamptz,
    received_at timestamptz not null default now(),
    raw_text text not null,
    parsed_json jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'received',
    error_message text
);

create table if not exists raw_imports (
    id bigserial primary key,
    data_source_id bigint references data_sources(id) on delete set null,
    task_id bigint references collection_tasks(id) on delete set null,
    run_id bigint references ingestion_runs(id) on delete set null,
    file_name varchar(400),
    file_type varchar(80),
    row_number bigint,
    received_at timestamptz not null default now(),
    payload_json jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'received',
    error_message text
);

create table if not exists standard_events (
    id bigserial primary key,
    raw_event_id bigint references raw_events(id) on delete set null,
    raw_log_id bigint references raw_logs(id) on delete set null,
    raw_import_id bigint references raw_imports(id) on delete set null,
    data_source_id bigint references data_sources(id) on delete set null,
    source_system varchar(120) not null,
    external_id varchar(200),
    event_type varchar(120) not null,
    occurred_at timestamptz,
    actor varchar(200),
    asset_ref varchar(200),
    subject_type varchar(80),
    subject_ref varchar(240),
    action varchar(120),
    result varchar(80),
    severity varchar(32) not null default 'info',
    risk_score integer not null default 0,
    normalized_json jsonb not null default '{}'::jsonb,
    extra_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(source_system, external_id)
);

alter table raw_events add constraint fk_raw_events_standard_event
    foreign key (standard_event_id) references standard_events(id) on delete set null;

create table if not exists alert_decisions (
    id bigserial primary key,
    standard_event_id bigint references standard_events(id) on delete cascade,
    rule_id bigint references rules(id) on delete set null,
    decision varchar(64) not null,
    severity varchar(32),
    risk_score integer,
    reason text,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists feedback_labels (
    id bigserial primary key,
    alert_id bigint references alerts(id) on delete cascade,
    standard_event_id bigint references standard_events(id) on delete set null,
    label varchar(64) not null,
    operator_name varchar(120),
    comment text,
    created_at timestamptz not null default now()
);

create table if not exists schema_scan_runs (
    id bigserial primary key,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    scan_type varchar(64) not null default 'metadata',
    status varchar(32) not null default 'running',
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    total_databases integer not null default 0,
    scanned_databases integer not null default 0,
    failed_databases integer not null default 0,
    total_tables integer not null default 0,
    scanned_tables integer not null default 0,
    failed_tables integer not null default 0,
    total_fields integer not null default 0,
    scanned_fields integer not null default 0,
    error_message text,
    result_json jsonb not null default '{}'::jsonb
);

alter table schema_tables add column if not exists scan_run_id bigint;
alter table schema_tables add column if not exists schema_name varchar(160);
alter table schema_tables add column if not exists table_type varchar(80) not null default 'table';
alter table schema_tables add column if not exists row_count bigint;
alter table schema_tables add column if not exists source_updated_at timestamptz;

alter table schema_fields add column if not exists scan_run_id bigint;
alter table schema_fields add column if not exists ordinal_position integer;
alter table schema_fields add column if not exists semantic_type varchar(120);
alter table schema_fields add column if not exists confidence integer;
alter table schema_fields add column if not exists is_candidate_key boolean not null default false;
alter table schema_fields add column if not exists is_time_candidate boolean not null default false;

create table if not exists template_matches (
    id bigserial primary key,
    schema_table_id bigint references schema_tables(id) on delete cascade,
    template_key varchar(160) not null,
    template_name varchar(200),
    confidence integer not null default 0,
    status varchar(32) not null default 'suggested',
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists ingestion_plans (
    id bigserial primary key,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    scan_run_id bigint references schema_scan_runs(id) on delete set null,
    name varchar(180) not null,
    status varchar(32) not null default 'draft',
    plan_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table alerts add column if not exists standard_event_id bigint;

create index if not exists idx_collection_tasks_source on collection_tasks(data_source_id);
create index if not exists idx_collection_tasks_status on collection_tasks(status);
create index if not exists idx_ingestion_runs_task on ingestion_runs(task_id);
create index if not exists idx_ingestion_runs_status on ingestion_runs(status);
create index if not exists idx_raw_events_source_external on raw_events(source_system, external_id);
create index if not exists idx_raw_events_received_at on raw_events(received_at);
create index if not exists idx_raw_events_status on raw_events(status);
create index if not exists idx_raw_logs_received_at on raw_logs(received_at);
create index if not exists idx_raw_imports_received_at on raw_imports(received_at);
create index if not exists idx_standard_events_source_external on standard_events(source_system, external_id);
create index if not exists idx_standard_events_occurred_at on standard_events(occurred_at);
create index if not exists idx_standard_events_event_type on standard_events(event_type);
create index if not exists idx_standard_events_severity on standard_events(severity);
create index if not exists idx_schema_scan_runs_source on schema_scan_runs(data_source_id);
create index if not exists idx_schema_scan_runs_status on schema_scan_runs(status);
create index if not exists idx_template_matches_table on template_matches(schema_table_id);
create index if not exists idx_ingestion_plans_source on ingestion_plans(data_source_id);
