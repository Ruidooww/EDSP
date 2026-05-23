alter table alerts add column if not exists alert_decision_id bigint;

alter table alerts
add constraint fk_alerts_alert_decision
foreign key (alert_decision_id)
references alert_decisions(id)
on delete set null;

create unique index if not exists uk_alerts_alert_decision_id
on alerts(alert_decision_id);

create index if not exists idx_alerts_alert_decision
on alerts(alert_decision_id);

create index if not exists idx_alerts_status_created
on alerts(status, created_at desc);

create index if not exists idx_alerts_severity_created
on alerts(severity, created_at desc);
