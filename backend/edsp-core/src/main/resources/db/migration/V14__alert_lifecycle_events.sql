alter table alerts add column if not exists assigned_to varchar(120);
alter table alerts add column if not exists acknowledged_at timestamptz;
alter table alerts add column if not exists closed_at timestamptz;

create table if not exists alert_lifecycle_events (
    id bigserial primary key,
    alert_id bigint not null references alerts(id) on delete cascade,
    event_type varchar(32) not null,
    from_status varchar(32),
    to_status varchar(32),
    operator_name varchar(120) not null,
    assignee varchar(120),
    note text,
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_alert_lifecycle_event_type check (event_type in ('acknowledged', 'assigned', 'closed'))
);

create index if not exists idx_alert_lifecycle_events_alert_created
on alert_lifecycle_events(alert_id, created_at desc);

create index if not exists idx_alert_lifecycle_events_type_created
on alert_lifecycle_events(event_type, created_at desc);

create index if not exists idx_alerts_assigned_to
on alerts(assigned_to);
