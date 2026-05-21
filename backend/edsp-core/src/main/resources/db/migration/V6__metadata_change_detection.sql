alter table schema_tables add column if not exists lifecycle_status varchar(32) not null default 'active';
alter table schema_tables add column if not exists last_seen_at timestamptz;
alter table schema_tables add column if not exists source_removed_at timestamptz;

alter table schema_fields add column if not exists lifecycle_status varchar(32) not null default 'active';
alter table schema_fields add column if not exists last_seen_at timestamptz;
alter table schema_fields add column if not exists source_removed_at timestamptz;

create table if not exists schema_change_events (
    id bigserial primary key,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    scan_run_id bigint references schema_scan_runs(id) on delete cascade,
    schema_table_id bigint references schema_tables(id) on delete set null,
    schema_field_id bigint references schema_fields(id) on delete set null,
    object_type varchar(32) not null,
    change_type varchar(32) not null,
    object_name varchar(520) not null,
    severity varchar(32) not null default 'info',
    status varchar(32) not null default 'auto_accepted',
    previous_json jsonb not null default '{}'::jsonb,
    current_json jsonb not null default '{}'::jsonb,
    reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_schema_change_events_source on schema_change_events(data_source_id);
create index if not exists idx_schema_change_events_run on schema_change_events(scan_run_id);
create index if not exists idx_schema_change_events_status on schema_change_events(status);
create index if not exists idx_schema_change_events_change_type on schema_change_events(change_type);
create index if not exists idx_schema_tables_lifecycle_status on schema_tables(lifecycle_status);
create index if not exists idx_schema_fields_lifecycle_status on schema_fields(lifecycle_status);
