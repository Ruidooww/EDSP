alter table standard_events add column if not exists dedup_key varchar(128);

create unique index if not exists uk_standard_events_dedup_key
on standard_events(dedup_key);

alter table alerts add column if not exists standard_event_id bigint;

alter table alerts
add constraint fk_alerts_standard_event
foreign key (standard_event_id)
references standard_events(id)
on delete set null;

create index if not exists idx_alerts_standard_event
on alerts(standard_event_id);

alter table raw_logs add column if not exists payload_hash varchar(128);
alter table raw_imports add column if not exists payload_hash varchar(128);
alter table raw_imports add column if not exists file_hash varchar(128);
