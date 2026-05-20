alter table alerts add column if not exists source_system varchar(80);
alter table alerts add column if not exists external_id varchar(160);
alter table alerts add column if not exists alert_type varchar(80);
alter table alerts add column if not exists occurred_at timestamptz;
alter table alerts add column if not exists actor varchar(160);
alter table alerts add column if not exists asset_ref varchar(160);
alter table alerts add column if not exists policy_name varchar(200);

create index if not exists idx_alerts_source_external on alerts(source_system, external_id);
create index if not exists idx_alerts_occurred_at on alerts(occurred_at);
create index if not exists idx_alerts_alert_type on alerts(alert_type);
