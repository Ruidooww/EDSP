# Matched Alert Decision Auto Generation Readiness MVP

## 1. Purpose

This stage is docs-only / assessment-first. It does not implement automatic alert
generation and does not change production behavior.

The future target pipeline is:

```text
sync once / scheduled sync
-> newly inserted standard_events only
-> RuleDecisionAutoPipelineService
-> persisted matched alert_decisions only
-> MatchedAlertDecisionAutoPipelineService
-> existing AlertGenerationService
-> alerts
```

This document defines the future contract, candidate selection, transaction
boundary, idempotency expectations, notification boundary, alert lifecycle
boundary, safe reporting shape, and implementation acceptance criteria.

This stage does not modify backend code, frontend code, migrations, workflows,
scripts, docker-compose, transform runtime, ShadowRun, Precheck, activation gate,
transform rules, valueMap, or chaining.

## 2. Assessment Sources

The readiness assessment inspected the current code and migrations:

- `RuleDecisionAutoPipelineService`
- `RuleDecisionRunner`
- `RuleEvaluationService`
- `AlertGenerationService`
- `AlertRepository`
- `AlertGenerationController`
- `AlertLifecycleService`
- `V4__core_event_pipeline.sql`
- `V12__rule_decision_idempotency.sql`
- `V13__alert_generation_from_decisions.sql`
- `V14__alert_lifecycle_events.sql`
- `edsp-alert` notification services and controllers
- Existing `edsp-core` alert generation, rule decision, sync, and lifecycle tests

## 3. Confirmed Existing Facts

The following facts are confirmed by the current source:

1. `RuleDecisionAutoPipelineService.evaluateNewStandardEvents(...)` receives the
   newly inserted `standard_events` IDs from plan sync and evaluates only those
   IDs. Duplicate rows, `standardize_failed` rows, and no-source-row runs do not
   enter the new-standard-event automatic rule evaluation path.
2. `RuleDecisionRunner.run(...)` evaluates enabled rules and persists each result
   through `RuleDecisionRepository.upsert(...)`.
3. `alert_decisions.decision` supports `matched`, `not_matched`, `skipped`, and
   `error`. `V12__rule_decision_idempotency.sql` enforces this set.
4. `alert_decisions.rule_id` is nullable. Its foreign key uses
   `on delete set null`, so a retained decision can lose its rule reference after
   rule deletion.
5. `AlertGenerationService.generate(...)` exists and accepts
   `AlertGenerationRunRequest`.
6. `AlertGenerationService.generate(...)` creates alerts only for
   `decision = 'matched'`.
7. `AlertRepository.createFromDecision(...)` exists and is the current alert
   creation boundary.
8. `alerts.alert_decision_id` exists.
9. `V13__alert_generation_from_decisions.sql` creates the unique index
   `uk_alerts_alert_decision_id` on `alerts(alert_decision_id)`.
10. The manual endpoint `POST /api/core/alert-generations/run` exists and delegates
    to `AlertGenerationService.generate(...)`.
11. `AlertGenerationService` and `AlertRepository` do not call notification
    services and do not write `notification_deliveries`.
12. Alert notification writes are owned by `edsp-alert`, primarily through
    `AlertNotificationService`.
13. New alerts created by `AlertRepository.createFromDecision(...)` start with
    `status = 'open'`.
14. The existing lifecycle is manually managed: `open -> acknowledged -> closed`.
    Assignment is allowed for `open` or `acknowledged` alerts and does not change
    alert status.

The schema-level idempotency constraint exists, but that does not prove
PostgreSQL transaction recovery behavior under concurrent insert races.

## 4. Existing Reusable Boundary

Future implementation must reuse:

1. `AlertGenerationService.generate(...)`
2. `AlertGenerationRunRequest`
3. `AlertRepository.createFromDecision(...)`
4. `alerts.alert_decision_id`
5. `uk_alerts_alert_decision_id`
6. Manual endpoint `POST /api/core/alert-generations/run`

Future implementation must not copy or rewrite:

1. Alert title construction
2. Alert severity selection
3. Alert detail construction
4. External ID and deduplication logic
5. Alert idempotency logic
6. Alert lifecycle logic

If PostgreSQL concurrency verification proves the repository boundary
insufficient, that is a future implementation blocker. It is not a reason to
expand this readiness stage.

## 5. Future Service Recommendation

The recommended future service is:

```text
MatchedAlertDecisionAutoPipelineService
```

Recommended entry point:

```java
generateForNewStandardEvents(List<Long> newStandardEventIds)
```

The service should query only eligible candidate decisions:

```sql
select id
from alert_decisions
where standard_event_id in (...)
  and rule_id is not null
  and decision = 'matched'
order by id
```

The `rule_id is not null` predicate is required. Retained decisions may lose
their rule reference because `alert_decisions.rule_id` uses `on delete set null`.
Such decisions remain valid audit records but must not become automatic alert
generation candidates.

The `standard_event_id in (...)` predicate naturally excludes decisions that do
not reference a standard event. It also keeps the future service inside the
new-standard-events-only boundary and prevents automatic historical backfill.

Design reasons:

1. Candidate selection remains constrained by newly inserted standard events.
2. Decision IDs do not need to be exposed in sync reports.
3. `not_matched`, `skipped`, and `error` decisions never enter alert generation.
4. Duplicate standard events do not enter the new-event chain.
5. Decisions with missing rules do not enter alert generation.
6. `uk_alerts_alert_decision_id` remains the final schema-level idempotency guard.
7. Alerts are never created directly from `raw_events` or `standard_events`.

## 6. Decision Eligibility Contract

Future automatic alert generation may process only:

```text
decision = 'matched'
and rule_id is not null
and standard_event_id belongs to the current run's newly inserted standard_events
```

Future automatic alert generation must not process:

1. `decision = 'not_matched'`
2. `decision = 'skipped'`
3. `decision = 'error'`
4. `rule_id is null`
5. Decisions whose rule reference is missing
6. Decisions whose standard event reference is missing
7. Decisions outside the current new-standard-event scope
8. Existing decisions associated with duplicate standard events

For `decision = 'error'`:

1. Keep the `alert_decisions` row.
2. Do not create an alert.
3. Do not trigger notifications.
4. Do not change rule decision semantics.
5. Do not close an existing alert.

## 7. PostgreSQL Idempotency Follow-up Blocker

```text
Status: needs follow-up

The unique index exists, but PostgreSQL concurrent insert recovery inside
PROPAGATION_NESTED must be verified before auto generation is enabled.
```

Current source behavior:

1. `uk_alerts_alert_decision_id` provides schema-level idempotency.
2. `AlertRepository.createFromDecision(...)` checks for an existing alert before
   insert.
3. On `DataIntegrityViolationException`, the repository immediately queries the
   existing alert and returns it as `action = 'existing'`.
4. In PostgreSQL, a unique violation may leave the current transaction aborted
   until rollback to a savepoint.
5. A query performed before savepoint recovery may not be usable.
6. Existing H2 idempotency tests are not PostgreSQL nested transaction
   concurrency proof.

Required future path:

1. Add a PostgreSQL transaction concurrency verification before enabling
   automatic generation.
2. If verification passes, connect `MatchedAlertDecisionAutoPipelineService`.
3. If verification fails, apply minimal `AlertRepository` hardening.
4. Prefer `ON CONFLICT DO NOTHING` or an equivalent savepoint-safe recovery
   strategy.
5. Re-run PostgreSQL concurrency verification after hardening.

Existing unique index provides schema-level idempotency, but PostgreSQL
transaction recovery behavior remains a follow-up blocker.

## 8. Ordering And Transaction Boundary

Future automatic generation order is fixed:

```text
persist raw_events
-> persist standard_events
-> auto evaluate rules for new standard_events
-> persist alert_decisions
-> auto generate alerts for persisted eligible matched decisions of new standard_events
```

Recommended transaction boundary:

1. Process each eligible matched decision in an independent nested transaction /
   JDBC savepoint.
2. Call the existing `AlertGenerationService` for each candidate.
3. A single alert creation failure must not roll back `raw_events`.
4. A single alert creation failure must not roll back `standard_events`.
5. A single alert creation failure must not roll back persisted
   `alert_decisions`.
6. A single alert creation failure must not stop other matched decisions.
7. Any single alert creation failure downgrades the sync run to `warning`.
8. Do not add a retry job.
9. Do not add a queue.
10. Do not add an outbox.
11. Do not perform historical backfill.

Automatic alert generation is a downstream enhancement of rule decisions. It
must not invalidate completed ingestion or persisted decision results.

This MVP path does not use after-commit processing or an outbox because either
approach requires compensation, retry, recovery, and additional persisted state.
Those capabilities require a separate readiness stage.

## 9. Future Safe Summary

Future sync `report_json` should add an additive safe summary:

```json
{
  "alertGenerationAuto": {
    "mode": "new_standard_event_matched_decisions_only",
    "status": "passed",
    "candidateDecisionCount": 2,
    "createdAlertCount": 1,
    "existingAlertCount": 1,
    "failedDecisionCount": 0
  }
}
```

Field semantics:

- `mode`: fixed value `new_standard_event_matched_decisions_only`
- `status`: `skipped`, `passed`, or `warning`
- `candidateDecisionCount`: number of eligible matched decision candidates
- `createdAlertCount`: number of newly created alerts
- `existingAlertCount`: number of idempotency hits for existing alerts
- `failedDecisionCount`: number of eligible matched decisions whose alert
  generation failed

Status rules:

1. No eligible matched decision: `status = 'skipped'`.
2. Every candidate created or already exists: `status = 'passed'`.
3. Any candidate fails: `status = 'warning'`.
4. `existingAlertCount` is a normal idempotency result, not a warning.

Optional `errorsByType` may contain only the fixed safe code:

```text
alert_generation_auto_failed
```

Optional `errorMessage` may contain only the fixed safe message:

```text
Automatic alert generation failed
```

The summary must not contain:

1. Decision IDs
2. Alert ID lists
3. Raw rows
4. `payload_json`
5. Complete `normalized_json`
6. Complete `extra_json`
7. Rule expressions
8. SQL exceptions
9. Stack traces
10. Source configuration
11. JDBC URLs
12. Usernames or passwords
13. Environment variables
14. Secret-like content

## 10. Notification Boundary

Future automatic alert generation:

1. Must not call `NotificationService`.
2. Must not call `AlertNotificationService`.
3. Must not write `notification_deliveries`.
4. Must not automatically send webhooks.
5. Must not automatically send WeCom messages.
6. Must not automatically send Feishu messages.
7. Must not change existing manual notification APIs.
8. May create alerts only; it must not enter notification delivery.

If automatic notification becomes a requirement, define a separate stage:

```text
Alert Notification Auto Delivery Readiness MVP
```

or:

```text
Notification Dispatch From Alerts MVP
```

## 11. Alert Lifecycle Boundary

The automatic chain must not close, revoke, or reopen existing alerts.

If an `alert_decision` later changes from `matched` to another state, the
generated alert remains managed by the existing manual lifecycle.

Future automatic alert generation:

1. Creates an alert only from an eligible matched decision.
2. Does not close an alert if a decision changes to `not_matched`.
3. Does not close an alert if a decision changes to `skipped`.
4. Does not close an alert if a decision changes to `error`.
5. Does not close an alert if a rule is disabled.
6. Does not close an alert if a rule is deleted or `rule_id` becomes null.
7. Does not reopen a closed alert.
8. Does not revoke an acknowledged alert.
9. Does not assign an alert.
10. Does not close an alert.
11. Does not reopen an alert.
12. Leaves `open`, `acknowledged`, `closed`, and assignment behavior to the
    existing manual lifecycle.

## 12. Unchanged Boundaries

Future implementation must not:

1. Create alerts directly from `raw_events`.
2. Create alerts directly from `standard_events`.
3. Create alerts for `not_matched` decisions.
4. Create alerts for `skipped` decisions.
5. Create alerts for `error` decisions.
6. Create alerts for decisions where `rule_id is null`.
7. Automatically close existing alerts.
8. Automatically revoke existing alerts.
9. Automatically reopen existing alerts.
10. Modify activation gate behavior.
11. Modify ShadowRun behavior.
12. Modify Precheck behavior.
13. Modify transform runtime behavior.
14. Modify transform rules.
15. Modify valueMap.
16. Modify alert lifecycle status flow.
17. Introduce Kafka.
18. Introduce Redis.
19. Introduce ClickHouse.
20. Introduce AI.
21. Introduce a retry worker.
22. Introduce a queue.
23. Introduce an outbox.
24. Introduce historical backfill.
25. Change manual `POST /api/core/alert-generations/run` semantics.

## 13. Future Implementation Scope

Recommended next implementation stage:

```text
Matched Alert Decision Auto Generation MVP
```

Recommended next branch:

```text
codex/matched-alert-decision-auto-generation-mvp
```

Future prerequisites:

1. Verify PostgreSQL nested transaction concurrent idempotency recovery.
2. If verification fails, complete minimal `AlertRepository` hardening.
3. Keep `rule_id is not null` in candidate SQL.
4. Preserve the notification boundary.
5. Preserve the lifecycle boundary.

Suggested future production scope:

```text
backend/edsp-core/src/main/java/com/edsp/core/service/IngestionPlanSyncOnceService.java
backend/edsp-core/src/main/java/com/edsp/core/service/MatchedAlertDecisionAutoPipelineService.java
backend/edsp-core/src/test/**
```

If PostgreSQL concurrency verification fails, allow a separate or same-stage
minimal change with explicit tests:

```text
backend/edsp-core/src/main/java/com/edsp/core/service/AlertRepository.java
```

Frontend may add a small `alertGenerationAuto` summary display only if it is
needed. Future implementation must not trigger notifications, write
`notification_deliveries`, modify lifecycle, modify rules, modify
`RuleEvaluationService`, modify transform runtime, add migrations, perform
backfill, or add retry / outbox behavior.

## 14. Future Test Plan

The future implementation stage must cover:

1. A matched decision automatically creates an alert.
2. A matched decision with `rule_id is null` does not create an alert.
3. A `not_matched` decision does not create an alert.
4. A `skipped` decision does not create an alert.
5. An `error` decision does not create an alert.
6. Repeated processing of one decision does not create duplicate alerts.
7. PostgreSQL nested transaction concurrent processing does not create duplicate
   alerts.
8. H2 idempotency tests are not treated as PostgreSQL concurrency proof.
9. Existing alert hits increment `existingAlertCount`.
10. A single alert generation failure downgrades sync to `warning`.
11. A single failure does not roll back `raw_events`.
12. A single failure does not roll back `standard_events`.
13. A single failure does not roll back `alert_decisions`.
14. One failed matched decision does not stop other matched decisions.
15. Automatic generation does not write `notification_deliveries`.
16. Automatic generation does not call `NotificationService`.
17. Automatic generation does not call `AlertNotificationService`.
18. Automatic generation does not trigger webhook, WeCom, or Feishu delivery.
19. Manual `POST /api/core/alert-generations/run` behavior remains compatible.
20. `alertGenerationAuto` contains no decision IDs, raw rows, payloads, rule
    expressions, source configuration, or secret-like content.
21. No eligible matched decisions produces summary `status = 'skipped'`.
22. Existing alert idempotency hits preserve summary `status = 'passed'`.
23. Automatic generation does not close an existing open alert.
24. Automatic generation does not revoke an acknowledged alert.
25. Automatic generation does not reopen a closed alert.
26. A decision changing from `matched` to `not_matched` does not close an alert.
27. Rule deletion and `rule_id` becoming null do not close an existing alert.

## 15. Open Questions

The following items remain intentionally unresolved for later stages:

1. Does PostgreSQL nested transaction concurrent recovery require
   `AlertRepository` hardening?
2. Should the frontend display the future `alertGenerationAuto` summary?
3. Should backfill, retry, or outbox behavior be planned as separate future
   stages?
4. Should automatic notification delivery receive a separate readiness stage?

## 16. Final Recommendation

Do not enable automatic alert generation yet.

Proceed next with `Matched Alert Decision Auto Generation MVP` only after the
PostgreSQL nested transaction concurrency verification is complete. Reuse the
existing alert generation service, enforce eligible matched decision selection,
preserve schema-level idempotency, keep notifications manual, and leave alert
lifecycle transitions manual.
