create table if not exists ai_agent_runs (
    id bigserial primary key,
    agent_key varchar(100) not null,
    provider_key varchar(100) not null,
    theme varchar(100) not null,
    period varchar(30) not null,
    model_name varchar(160),
    status varchar(30) not null,
    source varchar(40) not null,
    input_summary_json text not null default '{}',
    output_summary_json text not null default '{}',
    warning_summary_json text not null default '[]',
    error_code varchar(100),
    created_by varchar(100),
    started_at timestamp not null default current_timestamp,
    finished_at timestamp
);

create index if not exists idx_ai_agent_runs_started_at
    on ai_agent_runs(started_at desc);

create index if not exists idx_ai_agent_runs_provider
    on ai_agent_runs(provider_key);

create index if not exists idx_ai_agent_runs_status
    on ai_agent_runs(status);

create index if not exists idx_ai_agent_runs_theme_period_started_at
    on ai_agent_runs(theme, period, started_at desc);

