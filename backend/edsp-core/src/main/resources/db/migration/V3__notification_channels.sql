create table if not exists notification_channels (
    id bigserial primary key,
    name varchar(160) not null,
    channel_type varchar(40) not null default 'webhook',
    endpoint_url text,
    description text,
    config_json jsonb not null default '{}'::jsonb,
    enabled boolean not null default true,
    status varchar(32) not null default 'draft',
    last_test_status varchar(32),
    last_test_message text,
    last_test_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists notification_deliveries (
    id bigserial primary key,
    channel_id bigint references notification_channels(id) on delete set null,
    alert_id bigint references alerts(id) on delete set null,
    title varchar(240) not null,
    severity varchar(32),
    status varchar(32) not null,
    response_code integer,
    response_body text,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists idx_notification_channels_type on notification_channels(channel_type);
create index if not exists idx_notification_channels_enabled on notification_channels(enabled);
create index if not exists idx_notification_deliveries_channel on notification_deliveries(channel_id);
create index if not exists idx_notification_deliveries_alert on notification_deliveries(alert_id);
create index if not exists idx_notification_deliveries_status on notification_deliveries(status);
