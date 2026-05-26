# Notification Secret Storage Runtime Verification

## Verification Context

- Date: 2026-05-26
- Branch: `codex/notification-secret-storage-runtime-verification-mvp`
- Baseline before runtime fix: `555bbe8 docs: update notification secret storage runtime verification`
- Source baseline under verification: `6f67774 docs: update handoff for notification secret storage foundation mvp`
- Planned Docker Compose project: `edsp`
- Actual Docker Compose project used: `edsp-pg-verify`

The original plan used `docker compose -p edsp ...`, but the Docker host already had an `edsp-pg-verify` runtime using fixed container names such as `edsp-postgres`, `edsp-core`, `edsp-alert`, `edsp-gateway`, and `edsp-frontend`. Because the compose file uses fixed `container_name` values, a second project cannot start the same services in parallel.

To follow the non-destructive rule, this verification did not delete containers, delete volumes, run `down -v`, or clear any database. Verification continued against the existing `edsp-pg-verify` runtime.

## First Verification Attempt: Stale Runtime

Initial `docker compose -p edsp-pg-verify ps` showed all expected services running and PostgreSQL healthy, but the runtime was stale.

Command:

```powershell
docker compose -p edsp-pg-verify exec postgres psql -U edsp -d edsp_pg_verify -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 8;"
```

Result summary:

- Latest applied runtime migration: `14 - alert lifecycle events`
- `15 - notification delivery reliability`: not present
- `16 - notification secret storage`: not present

`edsp-core` logs showed:

```text
Successfully validated 14 migrations
Current version of schema "public": 14
Schema "public" is up to date. No migration necessary.
```

V16 field check returned `0 rows`, and querying `secret_storage_status` failed because the column did not exist.

Conclusion: the first runtime was stale and could not prove Notification Secret Storage Foundation stability on PostgreSQL.

## Non-Destructive Rebuild / Restart

Per review instruction, the existing runtime was rebuilt without deleting containers manually, without deleting volumes, and without running `down -v`.

The first broad rebuild attempt hit a Maven Central TLS / handshake failure. No code or database changes were made for that failure.

A narrower non-destructive rebuild was then used for `edsp-core`:

```powershell
docker compose -p edsp-pg-verify up --build -d edsp-core
```

This rebuilt `edsp-core`, but the recreated service used the compose default database name `edsp`, while the existing volume only contained `edsp_pg_verify`. PostgreSQL logs showed:

```text
database "edsp" does not exist
```

Diagnostic commands confirmed:

```text
existing databases: edsp_pg_verify, postgres, template0, template1
edsp-core SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/edsp
postgres POSTGRES_DB: edsp
```

To avoid hand-editing the database and to keep using the existing verification DB, the compose commands were rerun with a process-local environment variable:

```powershell
$env:POSTGRES_DB='edsp_pg_verify'; docker compose -p edsp-pg-verify up -d edsp-core
$env:POSTGRES_DB='edsp_pg_verify'; docker compose -p edsp-pg-verify up --build -d edsp-alert edsp-gateway frontend
```

This did not delete any volume and did not manually modify PostgreSQL.

## V16 Migration Result

`edsp-core` logs after the non-destructive rebuild showed successful V16 migration:

```text
Database: jdbc:postgresql://postgres:5432/edsp_pg_verify (PostgreSQL 16.14)
Successfully validated 16 migrations
Current version of schema "public": 14
Migrating schema "public" to version "15 - notification delivery reliability"
Migrating schema "public" to version "16 - notification secret storage"
Successfully applied 2 migrations to schema "public", now at version v16
```

Follow-up logs showed:

```text
Successfully validated 16 migrations
Current version of schema "public": 16
Schema "public" is up to date. No migration necessary.
```

Database verification:

```powershell
docker compose -p edsp-pg-verify exec postgres psql -U edsp -d edsp_pg_verify -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 8;"
```

Result summary:

- `16 - notification secret storage`: `success = true`
- `15 - notification delivery reliability`: `success = true`
- `14 - alert lifecycle events`: `success = true`

V16 field verification:

```text
endpoint_masked             | text
endpoint_secret_ciphertext  | text
endpoint_secret_key_version | character varying
secret_storage_status       | character varying
```

Secret storage status distribution:

```text
secret_storage_status | count
missing               | 5
```

Legacy / missing channel check:

```text
id | channel_type | secret_storage_status | has_endpoint_url | has_ciphertext
1  | webhook      | missing               | false            | false
2  | wecom        | missing               | false            | false
3  | feishu       | missing               | false            | false
4  | sms          | missing               | false            | false
5  | email        | missing               | false            | false
```

Conclusion: V16 migration and the notification secret storage schema are verified on the real PostgreSQL runtime.

## Runtime Blocker And Minimal Fix

After rebuilding the full service chain against `edsp_pg_verify`, `edsp-alert` initially failed to start and entered a restart loop.

Blocker log:

```text
Error creating bean with name 'notificationService'
Unsatisfied dependency expressed through constructor parameter 2:
Error creating bean with name 'notificationSecretStore'
Failed to instantiate [com.edsp.alert.service.NotificationSecretStore]: No default constructor found
Caused by: java.lang.NoSuchMethodException: com.edsp.alert.service.NotificationSecretStore.<init>()
```

Root cause:

- `NotificationSecretStore` had two constructors.
- The production constructor accepted `@Value("${edsp.notification.secret.master-key:}")`, but it was not annotated with `@Autowired`.
- Spring selected the default instantiation path and tried to call a no-arg constructor that did not exist.

Minimal fix:

- Added `@Autowired` to the production `NotificationSecretStore(@Value(...))` constructor.
- Added `NotificationSecretStoreSpringContextTest` to verify Spring can create the bean through the configured master-key constructor.

No notification send / retry API semantics were changed. No V16 migration, alert lifecycle, partial update, backfill, cleanup, Vault, or KMS changes were made.

## Runtime Service Startup Result After Fix

Rebuild command:

```powershell
$env:POSTGRES_DB='edsp_pg_verify'; docker compose -p edsp-pg-verify up --build -d edsp-alert edsp-gateway frontend
```

`docker compose -p edsp-pg-verify ps` showed:

- `edsp-postgres`: `Up` and `healthy`
- `edsp-core`: `Up`
- `edsp-alert`: `Up`
- `edsp-gateway`: `Up`
- `frontend`: `Up`, exposed on `localhost:18080`
- `edsp-auth`: `Up`
- `edsp-report`: `Up`

`edsp-alert` logs showed:

```text
Tomcat started on port 8083 (http) with context path '/'
Started AlertApplication
```

Conclusion: the `edsp-alert` Docker runtime startup blocker is fixed.

## HTTP Smoke Test After Fix

Commands:

```powershell
curl.exe -i http://localhost:18080/api/notifications/channels
curl.exe -i "http://localhost:18080/api/notifications/deliveries?limit=10"
curl.exe -i http://localhost:18080/api/core/overview
```

Result summary:

- `GET /api/notifications/channels`: `200 OK`
- `GET /api/notifications/deliveries?limit=10`: `200 OK`
- `GET /api/core/overview`: `200 OK`

Sensitive response scan found no forbidden response patterns:

```text
endpoint_secret_ciphertext
endpoint_secret_key_version
"endpoint_url"
WESECRET
FEISHUTOKEN
WEBHOOKTOKEN
REAL_KEY
REAL_TOKEN
access_token=
signature=
Authorization
Bearer
https://qyapi.weixin.qq.com
open.feishu.cn/open-apis/bot/v2/hook/
```

## Secret Key Runtime Check

This verification did not write test webhook / WeCom / Feishu endpoints and did not call external webhooks.

The runtime did not set `EDSP_NOTIFICATION_SECRET_KEY`. This is allowed for service startup according to the stage design. Runtime write behavior for encrypted channel creation remains covered by unit tests rather than external webhook smoke tests.

## Code Verification

Focused failing test before fix:

```powershell
mvn -pl edsp-alert -Dtest=NotificationSecretStoreSpringContextTest test
```

Observed failure before the fix:

```text
Failed to instantiate [com.edsp.alert.service.NotificationSecretStore]: No default constructor found
```

Focused test after fix:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

Full backend tests:

```powershell
cd backend
mvn -pl edsp-alert -am test
mvn -pl edsp-core -am test
```

Results:

- `edsp-alert`: passed, `Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`
- `edsp-core`: passed, `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`

Frontend build:

```powershell
cd frontend
npm.cmd run build
```

Result:

- Passed
- Existing Vite chunk size warning only

## Changes Made

Runtime fix:

```text
backend/edsp-alert/src/main/java/com/edsp/alert/service/NotificationSecretStore.java
backend/edsp-alert/src/test/java/com/edsp/alert/service/NotificationSecretStoreSpringContextTest.java
```

Verification document update:

```text
docs/notification-secret-storage-runtime-verification.md
```

No `AGENTS.md` or `HANDOFF.md` changes were made.

## Verification Result

Runtime verification is now successful for the planned scope:

- V16 Flyway migration on real PostgreSQL: passed.
- V16 notification secret storage fields: passed.
- `edsp-alert` Docker runtime startup: passed after minimal Spring constructor injection fix.
- Notification API smoke tests: passed.
- Core overview smoke test: passed.
- Sensitive response scan: passed.
- Backend tests: passed.
- Frontend build: passed.

## Known Risks

- Fixed `container_name` values prevent multiple compose projects from running the same EDSP stack in parallel. If parallel runtime verification is required, plan a separate compose container-name hardening stage.
- Existing `edsp-pg-verify` volume contains `edsp_pg_verify`, while the default compose database name is `edsp`; future rebuilds must explicitly align `POSTGRES_DB` or use a clean non-destructive verification database strategy.
- This stage did not do partial update.
- This stage did not do backfill / cleanup.
- This stage did not do key rotation.
- This stage did not connect Vault / KMS.
- This stage did not delete legacy plaintext `endpoint_url`.
- Runtime smoke tests do not prove real external webhook reachability.
- Any runtime verification test key must not be used in production.

## Next Recommendation

Proceed to review. If approved, this branch can be merged as Notification Secret Storage Runtime Verification MVP.

After merge, update `HANDOFF.md` on `master` only as the post-merge docs step.
