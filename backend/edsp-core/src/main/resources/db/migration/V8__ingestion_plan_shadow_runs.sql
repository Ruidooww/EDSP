create table if not exists ingestion_plan_shadow_runs (
    id bigserial primary key,
    ingestion_plan_id bigint not null references ingestion_plans(id) on delete cascade,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    status varchar(32) not null,
    sample_limit integer not null default 50,
    read_count integer not null default 0,
    success_count integer not null default 0,
    failed_count integer not null default 0,
    duplicate_count integer not null default 0,
    missing_required_count integer not null default 0,
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    duration_ms bigint not null default 0,
    error_message text,
    report_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_shadow_runs_plan_created
on ingestion_plan_shadow_runs(ingestion_plan_id, created_at desc);
