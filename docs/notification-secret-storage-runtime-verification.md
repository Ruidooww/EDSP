# Notification Secret Storage Runtime Verification

## Verification Context

- Date: 2026-05-26
- Branch: `codex/notification-secret-storage-runtime-verification-mvp`
- Commit verified: `6f67774`
- Planned Docker Compose project: `edsp`
- Actual Docker Compose project used: `edsp-pg-verify`

The original plan used `docker compose -p edsp ...`, but the current Docker host already had an `edsp-pg-verify` runtime using fixed container names such as `edsp-postgres`, `edsp-core`, `edsp-alert`, `edsp-gateway`, and `edsp-frontend`. Because the compose file uses fixed `container_name` values, a second project cannot start the same services in parallel.

To follow the non-destructive rule, this verification did not delete containers, delete volumes, run `down -v`, or clear any database. Verification continued against the existing healthy `edsp-pg-verify` runtime.

## Runtime Status

`docker compose -p edsp-pg-verify ps` showed all expected services running:

- `edsp-postgres`: `Up` and `healthy`
- `edsp-core`: `Up`
- `edsp-alert`: `Up`
- `edsp-gateway`: `Up`
- `edsp-frontend`: `Up`, exposed on `localhost:18080`
- `edsp-auth`: `Up`
- `edsp-report`: `Up`

PostgreSQL logs included `database system is ready to accept connections`.

The runtime database is `edsp_pg_verify`, confirmed from:

```powershell
docker compose -p edsp-pg-verify exec postgres printenv POSTGRES_DB
docker compose -p edsp-pg-verify exec edsp-core printenv SPRING_DATASOURCE_URL
```

## Flyway And V16 Verification

Flyway verification against the existing PostgreSQL runtime did not reach V16.

Command:

```powershell
docker compose -p edsp-pg-verify exec postgres psql -U edsp -d edsp_pg_verify -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 8;"
```

Result summary:

- Latest applied runtime migration: `14 - alert lifecycle events`
- `15 - notification delivery reliability`: not present in this runtime
- `16 - notification secret storage`: not present in this runtime

`edsp-core` logs also showed:

```text
Successfully validated 14 migrations
Current version of schema "public": 14
Schema "public" is up to date. No migration necessary.
```

This means the existing `edsp-pg-verify` containers/images are not running the current `master` build that contains V15/V16. Therefore, this runtime cannot prove Notification Secret Storage Foundation stability on PostgreSQL.

## Notification Secret Storage Field Check

Command:

```powershell
docker compose -p edsp-pg-verify exec postgres psql -U edsp -d edsp_pg_verify -c "select column_name, data_type from information_schema.columns where table_name = 'notification_channels' and column_name in ('endpoint_secret_ciphertext','endpoint_secret_key_version','endpoint_masked','secret_storage_status') order by column_name;"
```

Result:

```text
0 rows
```

Follow-up queries using `secret_storage_status` failed with:

```text
ERROR: column "secret_storage_status" does not exist
```

Conclusion: V16 fields are absent in the existing runtime database.

## Secret Key Runtime Check

Command:

```powershell
docker compose -p edsp-pg-verify exec edsp-alert printenv EDSP_NOTIFICATION_SECRET_KEY
```

Result:

```text
EDSP_NOTIFICATION_SECRET_KEY=<not set>
```

Because the runtime is still at schema V14 and lacks V16 fields, this stage did not run write-type channel creation tests. No test webhook, WeCom, or Feishu endpoint was written. No notification send or retry was executed.

## HTTP Smoke Test

The runtime is reachable through frontend port `18080`.

Commands:

```powershell
curl.exe -i http://localhost:18080
curl.exe -i http://localhost:18080/api/notifications/channels
curl.exe -i "http://localhost:18080/api/notifications/deliveries?limit=10"
curl.exe -i http://localhost:18080/api/core/overview
```

Result summary:

- `GET /`: `200 OK`
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
secret=
Authorization
Bearer
```

Note: because this runtime is at V14, the API also does not expose `secret_storage_status`.

## Code Verification

Backend tests:

```powershell
cd backend
mvn -pl edsp-alert -am test
mvn -pl edsp-core -am test
```

Results:

- `edsp-alert`: passed, `Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`
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

No runtime config or business code was changed.

Only this verification document was added:

```text
docs/notification-secret-storage-runtime-verification.md
```

No `AGENTS.md` or `HANDOFF.md` changes were made.

## Known Risks

- This verification used the existing `edsp-pg-verify` runtime because fixed `container_name` values prevented starting a parallel `edsp` project.
- The existing `edsp-pg-verify` runtime is stale relative to current `master`; it only contains Flyway migrations through V14.
- V16 was not verified on the existing PostgreSQL runtime because the running images/schema do not include V15/V16.
- Fixed `container_name` values prevent multiple compose projects from running the same EDSP stack in parallel. If parallel runtime verification is required, plan a separate compose container-name hardening stage.
- This stage did not do partial update.
- This stage did not do backfill / cleanup.
- This stage did not do key rotation.
- This stage did not connect Vault / KMS.
- This stage did not delete legacy plaintext `endpoint_url`.
- Runtime smoke tests do not prove real external webhook reachability.
- The documented test key must not be used in production.

## Next Recommendation

Before moving to Notification Secret Storage Hardening, run a controlled V16 runtime verification using one of these non-destructive options:

1. Rebuild and recreate the existing `edsp-pg-verify` service containers without deleting volumes, then rerun the V16 checks.
2. Use a separate compose variant or hardening change that removes fixed `container_name` values so a clean project can run in parallel.
3. Use an isolated temporary database/project specifically for clean V1-V16 migration verification.

Do not merge this runtime verification as a successful V16 PostgreSQL verification without addressing the stale runtime finding.
