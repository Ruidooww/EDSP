create table if not exists ingestion_plan_activations (
    id bigserial primary key,
    ingestion_plan_id bigint not null references ingestion_plans(id) on delete cascade,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    shadow_run_id bigint not null references ingestion_plan_shadow_runs(id) on delete cascade,
    status varchar(32) not null,
    activated_by varchar(120),
    activated_at timestamptz not null default now(),
    activation_reason text,
    deactivated_by varchar(120),
    deactivated_at timestamptz,
    deactivation_reason text,
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_ingestion_plan_activations_status
        check (status in ('active', 'deactivated'))
);

create index if not exists idx_plan_activations_plan_created
on ingestion_plan_activations(ingestion_plan_id, created_at desc);

create index if not exists idx_plan_activations_plan_status
on ingestion_plan_activations(ingestion_plan_id, status);
