create table if not exists ingestion_plan_sync_schedules (
    id bigserial primary key,
    ingestion_plan_id bigint not null references ingestion_plans(id) on delete cascade,
    activation_id bigint not null references ingestion_plan_activations(id) on delete cascade,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    status varchar(32) not null default 'enabled',
    interval_seconds integer not null default 300,
    sample_limit integer not null default 50,
    next_run_at timestamptz not null default now(),
    last_run_at timestamptz,
    last_sync_run_id bigint,
    last_status varchar(32),
    last_error_message text,
    consecutive_failures integer not null default 0,
    locked_at timestamptz,
    lock_owner varchar(120),
    created_by varchar(120),
    updated_by varchar(120),
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_ingestion_plan_sync_schedules_status
        check (status in ('enabled', 'paused')),
    constraint chk_ingestion_plan_sync_schedules_interval
        check (interval_seconds between 60 and 86400),
    constraint chk_ingestion_plan_sync_schedules_sample_limit
        check (sample_limit between 1 and 100)
);

create unique index if not exists uk_plan_sync_schedules_activation
on ingestion_plan_sync_schedules(activation_id);

create index if not exists idx_plan_sync_schedules_plan_created
on ingestion_plan_sync_schedules(ingestion_plan_id, created_at desc);

create index if not exists idx_plan_sync_schedules_due
on ingestion_plan_sync_schedules(status, next_run_at);

alter table ingestion_plan_sync_schedules
add constraint fk_plan_sync_schedules_last_sync_run
foreign key (last_sync_run_id) references ingestion_plan_sync_runs(id) on delete set null;

alter table ingestion_plan_sync_runs
add column if not exists schedule_id bigint references ingestion_plan_sync_schedules(id) on delete set null;

alter table ingestion_plan_sync_runs
add column if not exists trigger_type varchar(32) not null default 'manual';

alter table ingestion_plan_sync_runs
add constraint chk_ingestion_plan_sync_runs_trigger_type
check (trigger_type in ('manual', 'scheduled'));

create index if not exists idx_plan_sync_runs_schedule_created
on ingestion_plan_sync_runs(schedule_id, created_at desc);
