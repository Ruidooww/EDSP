create table if not exists app_users (
    id bigserial primary key,
    username varchar(120) not null unique,
    display_name varchar(160) not null,
    password_hash varchar(255),
    status varchar(32) not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists data_sources (
    id bigserial primary key,
    name varchar(160) not null,
    source_type varchar(64) not null,
    connection_kind varchar(64) not null,
    description text,
    config_json jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'draft',
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists schema_tables (
    id bigserial primary key,
    data_source_id bigint not null references data_sources(id) on delete cascade,
    table_name varchar(240) not null,
    category varchar(80),
    confirmation_status varchar(32) not null default 'pending',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(data_source_id, table_name)
);

create table if not exists schema_fields (
    id bigserial primary key,
    schema_table_id bigint not null references schema_tables(id) on delete cascade,
    field_name varchar(240) not null,
    field_type varchar(120) not null,
    nullable boolean not null default true,
    sample_value text,
    description text,
    created_at timestamptz not null default now(),
    unique(schema_table_id, field_name)
);

create table if not exists field_mappings (
    id bigserial primary key,
    schema_table_id bigint not null references schema_tables(id) on delete cascade,
    source_field varchar(240) not null,
    standard_field varchar(120) not null,
    transform_rule text,
    created_at timestamptz not null default now(),
    unique(schema_table_id, source_field, standard_field)
);

create table if not exists rules (
    id bigserial primary key,
    name varchar(180) not null,
    event_type varchar(80) not null,
    severity varchar(32) not null,
    expression text not null,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists alerts (
    id bigserial primary key,
    title varchar(240) not null,
    severity varchar(32) not null,
    status varchar(32) not null default 'open',
    rule_id bigint references rules(id) on delete set null,
    subject_type varchar(80),
    subject_ref varchar(160),
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists alert_notes (
    id bigserial primary key,
    alert_id bigint not null references alerts(id) on delete cascade,
    operator_name varchar(120) not null,
    note text not null,
    created_at timestamptz not null default now()
);

create table if not exists report_jobs (
    id bigserial primary key,
    report_type varchar(80) not null,
    title varchar(200) not null,
    status varchar(32) not null default 'pending',
    params_json jsonb not null default '{}'::jsonb,
    file_path varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists audit_logs (
    id bigserial primary key,
    actor varchar(120),
    action varchar(160) not null,
    target_type varchar(80),
    target_id varchar(120),
    detail_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
