# Notification Secret Storage Runtime Verification

## Verification Context

- Date: 2026-05-26
- Branch: `codex/notification-secret-storage-runtime-verification-mvp`
- Stage branch commit before this update: `456fa94 docs: add notification secret storage runtime verification`
- Source baseline under verification: `6f67774 docs: update handoff for notification secret storage foundation mvp`
- Planned Docker Compose project: `edsp`
- Actual Docker Compose project used: `edsp-pg-verify`

The original plan used `docker compose -p edsp ...`, but the current Docker host already had an `edsp-pg-verify` runtime using fixed container names such as `edsp-postgres`, `edsp-core`, `edsp-alert`, `edsp-gateway`, and `edsp-frontend`. Because the compose file uses fixed `container_name` values, a second project cannot start the same services in parallel.

To follow the non-destructive rule, this verification did not delete containers, delete volumes, run `down -v`, or clear any database. Verification continued against the existing `edsp-pg-verify` runtime.

## First Verification Attempt: Stale Runtime

Initial `docker compose -p edsp-pg-verify ps` showed all expected services running:

- `edsp-postgres`: `Up` and `healthy`
- `edsp-core`: `Up`
- `edsp-alert`: `Up`
- `edsp-gateway`: `Up`
- `edsp-frontend`: `Up`, exposed on `localhost:18080`
- `edsp-auth`: `Up`
- `edsp-report`: `Up`

PostgreSQL logs included:

```text
database system is ready to accept connections
```

The runtime database was `edsp_pg_verify`, confirmed from:

```powershell
docker compose -p edsp-pg-verify exec postgres printenv POSTGRES_DB
docker compose -p edsp-pg-verify exec edsp-core printenv SPRING_DATASOURCE_URL
```

Flyway verification against the existing runtime did not reach V16.

Command:

```powershell
docker compose -p edsp-pg-verify exec postgres psql -U edsp -d edsp_pg_verify -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 8;"
```

Result summary:

- Latest applied runtime migration: `14 - alert lifecycle events`
- `15 - notification delivery reliability`: not present
- `16 - notification secret storage`: not present

`edsp-core` logs also showed:

```text
Successfully validated 14 migrations
Current version of schema "public": 14
Schema "public" is up to date. No migration necessary.
```

V16 field check returned `0 rows`, and querying `secret_storage_status` failed because the column did not exist.

Conclusion: the first runtime was stale and could not prove Notification Secret Storage Foundation stability on PostgreSQL.

## Non-Destructive Rebuild / Restart

Per review instruction, the existing runtime was rebuilt without deleting containers manually, without deleting volumes, and without running `down -v`.

First rebuild command:

```powershell
docker compose -p edsp-pg-verify up --build -d postgres edsp-auth edsp-core edsp-alert edsp-report edsp-gateway frontend
```

This initially failed during Docker build because Maven dependency downloads from Maven Central hit a remote TLS / handshake failure. No code or database changes were made for that failure.

A narrower non-destructive rebuild was then used for `edsp-core`:

```powershell
docker compose -p edsp-pg-verify up --build -d edsp-core
```

This rebuilt `edsp-core` successfully, but the recreated service used the compose default database name `edsp`, while the existing volume only contained `edsp_pg_verify`. PostgreSQL logs showed:

```text
database "edsp" does not exist
```

Diagnostic commands confirmed:

```text
existing databases: edsp_pg_verify, postgres, template0, template1
edsp-core SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/edsp
postgres POSTGRES_DB: edsp
```

To avoid hand-editing the database and to keep using the existing verification DB, the compose command was rerun with an explicit process-local environment variable:

```powershell
$env:POSTGRES_DB='edsp_pg_verify'; docker compose -p edsp-pg-verify up -d edsp-core
```

This did not delete any volume and did not manually modify PostgreSQL. `edsp-core` then connected to `edsp_pg_verify`.

Finally, the service chain was rebuilt with the same process-local database setting:

```powershell
$env:POSTGRES_DB='edsp_pg_verify'; docker compose -p edsp-pg-verify up --build -d edsp-alert edsp-gateway frontend
```

Because of compose dependencies, this also rebuilt/recreated the existing `edsp-auth`, `edsp-core`, and `edsp-report` service containers. Volumes were not deleted.

## Second Verification Attempt: V16 Migration Result

`edsp-core` logs after the non-destructive rebuild showed successful V16 migration:

```text
Database: jdbc:postgresql://postgres:5432/edsp_pg_verify (PostgreSQL 16.14)
Successfully validated 16 migrations
Current version of schema "public": 14
Migrating schema "public" to version "15 - notification delivery reliability"
Migrating schema "public" to version "16 - notification secret storage"
Successfully applied 2 migrations to schema "public", now at version v16
```

Follow-up logs after service-chain rebuild showed:

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

```powershell
docker compose -p edsp-pg-verify exec postgres psql -U edsp -d edsp_pg_verify -c "select column_name, data_type from information_schema.columns where table_name = 'notification_channels' and column_name in ('endpoint_secret_ciphertext','endpoint_secret_key_version','endpoint_masked','secret_storage_status') order by column_name;"
```

Result:

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
id | name             | channel_type | secret_storage_status | has_endpoint_url | has_ciphertext
1  | 安全运营 Webhook | webhook      | missing               | false            | false
2  | 企业微信值班群   | wecom        | missing               | false            | false
3  | 飞书安全群       | feishu       | missing               | false            | false
4  | 短信告警         | sms          | missing               | false            | false
5  | 邮件审计归档     | email        | missing               | false            | false
```

Conclusion: V16 migration and the notification secret storage schema are verified on the real PostgreSQL runtime.

## Runtime Service Startup Result

After rebuilding the full service chain against `edsp_pg_verify`, these services started:

- `edsp-postgres`: healthy
- `edsp-core`: started
- `edsp-auth`: started
- `edsp-report`: started
- `edsp-gateway`: started
- `frontend`: started

However, `edsp-alert` did not start successfully and entered a restart loop.

`edsp-alert` startup failure:

```text
Error creating bean with name 'notificationService'
Unsatisfied dependency expressed through constructor parameter 2:
Error creating bean with name 'notificationSecretStore'
Failed to instantiate [com.edsp.alert.service.NotificationSecretStore]: No default constructor found
Caused by: java.lang.NoSuchMethodException: com.edsp.alert.service.NotificationSecretStore.<init>()
```

This is a runtime startup blocker in the current Notification Secret Storage Foundation implementation. Local tests pass, but the Docker runtime cannot start `edsp-alert` from the rebuilt current image.

No business code was modified in this runtime verification stage.

## HTTP Smoke Test

Commands:

```powershell
curl.exe -i http://localhost:18080
curl.exe -i http://localhost:18080/api/notifications/channels
curl.exe -i "http://localhost:18080/api/notifications/deliveries?limit=10"
curl.exe -i http://localhost:18080/api/core/overview
```

Result summary:

- `GET /`: `200 OK`
- `GET /api/core/overview`: `200 OK`
- `GET /api/notifications/channels`: `500 Internal Server Error`
- `GET /api/notifications/deliveries?limit=10`: `500 Internal Server Error`

The notification API smoke tests fail because `edsp-alert` is restarting.

Sensitive response scan of the HTTP responses did not show:

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
secret=
Authorization
Bearer
```

## Secret Key Runtime Check

This verification did not write test webhook / WeCom / Feishu endpoints and did not call external webhooks.

The rebuilt runtime did not set `EDSP_NOTIFICATION_SECRET_KEY`. This is allowed for service startup according to the stage design, but `edsp-alert` failed earlier during Spring bean instantiation, so create / update encrypted channel behavior could not be smoke-tested through the runtime.

## Code Verification

Backend tests:

```powershell
cd backend
mvn -pl edsp-alert -am test
mvn -pl edsp-core -am test
```

Results will be refreshed after this document update in the final branch verification.
Refreshed result:

- `edsp-alert`: passed, `Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`
- `edsp-core`: passed, `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`

Frontend build:

```powershell
cd frontend
npm.cmd run build
```

Result will be refreshed after this document update in the final branch verification.
Refreshed result:

- Passed
- Existing Vite chunk size warning only

## Changes Made

No runtime config or business code was changed.

Only this verification document was updated:

```text
docs/notification-secret-storage-runtime-verification.md
```

No `AGENTS.md` or `HANDOFF.md` changes were made.

## Verification Result

Runtime verification is partially successful but not complete:

- V16 Flyway migration on real PostgreSQL: passed.
- V16 notification secret storage fields: passed.
- Frontend and core HTTP smoke tests: passed.
- Notification API runtime smoke tests: failed.
- `edsp-alert` current rebuilt image startup: failed.

This branch should not be treated as a successful Notification Secret Storage runtime verification until the `edsp-alert` startup blocker is fixed and the notification API smoke tests pass.

## Known Risks

- Fixed `container_name` values prevent multiple compose projects from running the same EDSP stack in parallel. If parallel runtime verification is required, plan a separate compose container-name hardening stage.
- Existing `edsp-pg-verify` volume contains `edsp_pg_verify`, while the default compose database name is `edsp`; future rebuilds must explicitly align `POSTGRES_DB` or use a clean non-destructive verification database strategy.
- `edsp-alert` fails to start after rebuilding the current image because `NotificationSecretStore` is not instantiated by Spring in the Docker runtime.
- Local unit tests did not catch the `edsp-alert` runtime startup failure.
- This stage did not do partial update.
- This stage did not do backfill / cleanup.
- This stage did not do key rotation.
- This stage did not connect Vault / KMS.
- This stage did not delete legacy plaintext `endpoint_url`.
- Runtime smoke tests do not prove real external webhook reachability.
- Any runtime verification test key must not be used in production.

## Next Recommendation

Before moving to Notification Secret Storage Hardening, fix the `edsp-alert` startup blocker in a focused stage or review patch, then rerun this runtime verification:

1. Rebuild `edsp-alert` with the fix.
2. Confirm `edsp-alert` starts against `edsp_pg_verify`.
3. Confirm `/api/notifications/channels` returns non-500 and does not expose secret fields.
4. Confirm `/api/notifications/deliveries?limit=10` returns non-500 and does not expose secret fields.
5. Rerun backend tests, frontend build, `git diff --check`, and `git status --short --branch`.

Do not merge this runtime verification as a successful runtime verification while `edsp-alert` startup remains blocked.
