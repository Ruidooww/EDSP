alter table notification_deliveries
    add column if not exists failure_type varchar(80);

alter table notification_deliveries
    add column if not exists failure_reason text;

alter table notification_deliveries
    add column if not exists retryable boolean not null default false;

alter table notification_deliveries
    add column if not exists retry_of_delivery_id bigint references notification_deliveries(id) on delete set null;

alter table notification_deliveries
    add column if not exists retry_count integer not null default 0;

create index if not exists idx_notification_deliveries_failure_type
on notification_deliveries(failure_type);

create index if not exists idx_notification_deliveries_status_retryable
on notification_deliveries(status, retryable);

create index if not exists idx_notification_deliveries_retry_of
on notification_deliveries(retry_of_delivery_id);
