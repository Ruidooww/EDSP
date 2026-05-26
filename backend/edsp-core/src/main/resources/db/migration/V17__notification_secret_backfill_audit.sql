create table if not exists notification_secret_backfill_runs (
    id bigserial primary key,
    mode varchar(32) not null,
    status varchar(32) not null,
    confirmation_accepted boolean not null default false,
    requested_by varchar(128),
    requested_at timestamptz not null default now(),
    started_at timestamptz,
    completed_at timestamptz,
    total_requested integer not null default 0,
    eligible_count integer not null default 0,
    migrated_count integer not null default 0,
    skipped_count integer not null default 0,
    failed_count integer not null default 0,
    failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_notification_secret_backfill_runs_mode
        check (mode in ('manual_channel_ids')),
    constraint chk_notification_secret_backfill_runs_status
        check (status in ('running', 'completed', 'completed_with_failures', 'failed'))
);

create table if not exists notification_secret_backfill_items (
    id bigserial primary key,
    run_id bigint not null,
    channel_id bigint not null,
    channel_type varchar(40),
    before_secret_storage_status varchar(32),
    after_secret_storage_status varchar(32),
    endpoint_masked text,
    item_status varchar(32) not null,
    failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_notification_secret_backfill_items_run
        foreign key (run_id) references notification_secret_backfill_runs(id),
    constraint chk_notification_secret_backfill_items_status
        check (item_status in ('migrated', 'skipped', 'failed'))
);

create index if not exists idx_notification_secret_backfill_items_run_id
on notification_secret_backfill_items(run_id);

create index if not exists idx_notification_secret_backfill_items_channel_id
on notification_secret_backfill_items(channel_id);

create index if not exists idx_notification_secret_backfill_runs_created_at
on notification_secret_backfill_runs(created_at);

create index if not exists idx_notification_secret_backfill_runs_status
on notification_secret_backfill_runs(status);
