delete from alert_decisions
where id in (
    select id
    from (
        select id,
               row_number() over (
                   partition by standard_event_id, rule_id
                   order by created_at desc, id desc
               ) as duplicate_rank
        from alert_decisions
        where standard_event_id is not null
          and rule_id is not null
    ) ranked_decisions
    where duplicate_rank > 1
);

update alert_decisions
set decision = 'error'
where decision is null
   or decision not in ('matched', 'not_matched', 'skipped', 'error');

create unique index if not exists uk_alert_decisions_event_rule
on alert_decisions(standard_event_id, rule_id);

create index if not exists idx_alert_decisions_event_created
on alert_decisions(standard_event_id, created_at desc);

create index if not exists idx_alert_decisions_decision_created
on alert_decisions(decision, created_at desc);

alter table alert_decisions
add constraint chk_alert_decisions_decision
check (decision in ('matched', 'not_matched', 'skipped', 'error'));
