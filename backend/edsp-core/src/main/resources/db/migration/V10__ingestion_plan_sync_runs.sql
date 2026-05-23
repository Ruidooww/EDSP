create table if not exists ingestion_plan_sync_runs (
    id bigserial primary key,
    ingestion_plan_id bigint not null references ingestion_plans(id) on delete cascade,
    activation_id bigint not null references ingestion_plan_activations(id) on delete cascade,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    shadow_run_id bigint references ingestion_plan_shadow_runs(id) on delete set null,
    ingestion_run_id bigint references ingestion_runs(id) on delete set null,
    status varchar(32) not null,
    sample_limit integer not null default 100,
    read_count bigint not null default 0,
    success_count bigint not null default 0,
    failed_count bigint not null default 0,
    duplicate_count bigint not null default 0,
    raw_count bigint not null default 0,
    standard_count bigint not null default 0,
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    duration_ms bigint not null default 0,
    error_message text,
    report_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_ingestion_plan_sync_runs_status
        check (status in ('passed', 'warning', 'blocked', 'failed'))
);

create index if not exists idx_plan_sync_runs_plan_created
on ingestion_plan_sync_runs(ingestion_plan_id, created_at desc);

create index if not exists idx_plan_sync_runs_activation_created
on ingestion_plan_sync_runs(activation_id, created_at desc);

create index if not exists idx_plan_sync_runs_ingestion_run
on ingestion_plan_sync_runs(ingestion_run_id);
