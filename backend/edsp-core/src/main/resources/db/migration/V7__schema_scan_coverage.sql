alter table schema_scan_runs add column if not exists limited boolean not null default false;
alter table schema_scan_runs add column if not exists coverage_rate numeric(7,4);
