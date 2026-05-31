# Matched Alert Decision Auto Generation PostgreSQL Concurrency Verification

## 1. Purpose

This stage verifies and minimally hardens the PostgreSQL concurrency boundary
that future matched alert decision auto generation will reuse.

This stage does not enable automatic alert generation. It does not add
`MatchedAlertDecisionAutoPipelineService`, connect matched decisions to sync,
trigger notifications, write `notification_deliveries`, or change alert
lifecycle behavior.

## 2. Verification Strategy

The verification uses:

1. An isolated Docker Compose project.
2. A dedicated PostgreSQL 16 database named `edsp_alert_pg_verify`.
3. A localhost-only PostgreSQL binding.
4. Existing Flyway migrations.
5. A dedicated JUnit test:
   `AlertRepositoryPostgresqlConcurrencyTest`.
6. Two concurrent workers using independent outer transactions and nested
   transactions backed by JDBC savepoints.
7. A barrier that ensures both workers observe that the alert does not yet
   exist before they compete for the same `alert_decision_id`.

The test calls the real `AlertRepository.createFromDecision(...)` method. It
does not mock repository insertion or conflict recovery.

The stage intentionally does not introduce Testcontainers. It also does not add
a GitHub workflow or required check. The goal is a focused manual Compose
verification asset for the repository boundary.

## 3. Initial RED Result

The first meaningful PostgreSQL concurrency run reproduced the readiness
blocker.

The current repository behavior attempted a normal insert, caught the unique
constraint failure, and immediately queried the existing alert. PostgreSQL
rejected that query because the nested transaction remained aborted until
savepoint rollback.

Safe failure summary:

```text
Initial result: RED
Failure type: transaction_aborted_after_unique_conflict
Affected boundary: AlertRepository.createFromDecision(...)
Secret or payload exposure: none recorded in the safe summary
```

Before the blocker evaluation, the PostgreSQL fixture helper was adjusted to
read the generated `id` from a multi-column generated-key map. This was a test
fixture compatibility correction, not a production behavior change.

## 4. Minimal Hardening

`AlertRepository.createFromDecision(...)` now uses:

```sql
insert into alerts(...)
values (...)
on conflict do nothing
```

The conflict path is savepoint-safe:

1. If the insert affects one row, the repository returns `action=created`.
2. If the insert affects zero rows, the repository queries the alert for the
   same `alert_decision_id` and returns `action=existing`.
3. If the insert affects zero rows but no alert exists for the same decision,
   the repository throws a fixed safe `DataIntegrityViolationException`.
4. Foreign-key, not-null, JSON, and other non-conflict integrity failures are
   not masked as existing alerts.

The hardening preserves the existing repository API and
`AlertGenerationService.generate(...)` semantics. It does not modify title,
severity, detail, external ID, notification, or lifecycle behavior.

## 5. Final PostgreSQL Result

The final Compose verification passed.

```text
PostgreSQL concurrency recovery: PASS
Concurrent workers: 2
Created result count: 1
Existing result count: 1
Final alert count for decision: 1
notification_deliveries count: 0
Alert status: open
acknowledged_at: null
closed_at: null
assigned_to: null
Outer transaction usable after race: yes
Lifecycle side effects: none
```

The safe runtime summary contains only:

```json
{
  "verification": "alert_generation_postgresql_concurrency",
  "status": "PASS",
  "composeProject": "<isolated-project>",
  "postgresPort": 15432,
  "testClass": "AlertRepositoryPostgresqlConcurrencyTest",
  "createdAlertCount": 1,
  "existingAlertCount": 1,
  "notificationDeliveryCount": 0,
  "postgresConcurrencyRecovery": "PASS",
  "hardeningApplied": true
}
```

The summary does not contain a DB dump, JDBC password, full environment,
stack trace, SQL exception text, raw payload, alert detail JSON, notification
payload, source configuration, or secret-like content.

## 6. Local Verification Command

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify-alert-generation-postgresql-concurrency.ps1 -PostgresPort 15432 -FinalAction Stop
```

The script:

1. Generates a unique Compose project unless one is explicitly provided.
2. Rejects a reused Compose project.
3. Rejects an occupied host port.
4. Starts only the dedicated PostgreSQL service.
5. Waits for PostgreSQL readiness.
6. Runs only `AlertRepositoryPostgresqlConcurrencyTest`.
7. Writes one safe ignored `summary.json`.
8. Stops the verification PostgreSQL container when `FinalAction=Stop`.
9. Does not delete containers or volumes.
10. Falls back to the OS temp directory if the configured artifact root is not
    ignored by Git.

The PostgreSQL test only allows a localhost JDBC URL targeting the dedicated
`edsp_alert_pg_verify` database before it runs Flyway cleanup and migrations.

## 7. Unchanged Boundaries

This stage does not:

1. Enable automatic alert generation.
2. Add `MatchedAlertDecisionAutoPipelineService`.
3. Modify sync once or scheduled sync.
4. Trigger `NotificationService` or `AlertNotificationService`.
5. Write `notification_deliveries`.
6. Change alert lifecycle behavior.
7. Add a migration.
8. Modify frontend code.
9. Add Testcontainers.
10. Modify GitHub workflows.
11. Modify the main `docker-compose.yml`.
12. Add retry, queue, outbox, or backfill behavior.

## 8. Next Stage

The PostgreSQL nested-transaction concurrency blocker is closed.

The recommended next stage is:

```text
Matched Alert Decision Auto Generation MVP
```

That stage may connect:

```text
new standard_events
-> persisted matched alert_decisions
-> MatchedAlertDecisionAutoPipelineService
-> existing AlertGenerationService
-> alerts
```

Notifications and alert lifecycle mutations must remain disabled unless they
are planned in separate stages.
