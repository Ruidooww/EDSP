# Precheck Real Runtime Alignment Assessment MVP

## Summary

This document assesses whether `IngestionPlanPrecheckService` should be aligned with the real transform runtime.

This stage is docs-only assessment. It does not implement real runtime Precheck.

This stage does not:

- Modify `backend/**`.
- Modify `frontend/**`.
- Modify `docker-compose.yml`.
- Modify `.github/workflows/**`.
- Modify `scripts/**`.
- Add or modify migrations.
- Modify `AGENTS.md`.
- Modify `HANDOFF.md` before merge.
- Inject `TransformRuntimeClient` into Precheck.
- Call `TransformRuntimeClient` from Precheck.
- Call remote transform service from Precheck.
- Modify `edsp-transform-contract` DTOs.
- Modify `edsp-transform-service` HTTP API.
- Delete `edsp-core -> edsp-transform`.

Current recommendation:

```text
Do not implement real runtime Precheck now.
Keep Precheck as dry-run / schema metadata validation.
If implementation is pursued later, evaluate Option C first.
Create a separate Precheck Real Runtime Alignment Implementation MVP before changing code.
```

## Current State

### Precheck

`IngestionPlanPrecheckService` currently performs dry-run / schema metadata validation.

Current behavior:

- Loads the ingestion plan and data source metadata.
- Requires plan status `approved` or `shadow_ready`.
- Parses `plan_json`.
- Validates plan mode.
- Validates source table existence and lifecycle status.
- Validates mapped source fields against active schema metadata.
- Validates required fields and dedup strategy from the plan.
- Validates shadow-only sync guard.
- Returns a report with `checks`, `blockers`, `warnings`, `standardEventPreview`, and summary metadata.

Current Precheck boundaries:

- Does not inject `TransformRuntimeClient`.
- Does not call `TransformRuntimeClient`.
- Does not call `edsp-transform-service`.
- Does not depend on remote or fallback runtime availability.
- Does not write `raw_events`.
- Does not write `standard_events`.
- Does not write `alert_decisions`.
- Does not write `alerts`.
- Does not change activation state.
- Does not change sync run state.

### ShadowRun

`IngestionPlanShadowRunService` has already been aligned to `TransformRuntimeClient`.

Current ShadowRun behavior:

- Runs Precheck first.
- Samples source rows only after Precheck is not blocked.
- Builds a `BatchTransformRequest` with sampled rows, mapping plan, dedup fields, and `syncMode=shadow_run`.
- Calls `TransformRuntimeClient`.
- Uses runtime response to build preview, errors, duplicate-in-sample detection, and summary.
- Uses runtime draft `dedupKey` for duplicate detection.
- Uses SHA-256 for `dedupKeyPreview`.
- Keeps safe preview masking / hashing for sensitive values and raw-like fields.
- On runtime failure, persists the shadow run as `failed` with sanitized `errorMessage`.
- Does not write `raw_events`, `standard_events`, `alert_decisions`, or `alerts`.

### TransformPlanSupport

`TransformPlanSupport` is a request / plan assembly helper.

Current responsibilities:

- Parse `plan_json`.
- Extract `fieldMappings`.
- Extract `dedupFields`.
- Extract `selectedFields`.
- Build `TransformMappingPlanDto`.
- Build `TransformOptionsDto`.

It does not:

- Normalize severity.
- Parse `occurredAt`.
- Build `dedupKey`.
- Reimplement transform logic.
- Reference `StandardEventTransformService`.
- Reference `com.edsp.transform.standardevent.*`.

### Dependency Guard

`TransformRuntimeDependencyGuardTest` currently enforces:

- Only explicit bridge files may reference the local transform engine.
- `edsp-core` main code must not reference `edsp-transform-service` Java packages.
- `edsp-core/pom.xml` must not depend on `edsp-transform-service`.
- `IngestionPlanPrecheckService` must not depend on `TransformRuntimeClient`.
- `IngestionPlanPrecheckService` must not call a real transform runtime.

This means any future Precheck real runtime implementation must intentionally update the guard in a dedicated implementation stage. It must not be slipped in during assessment.

## Current Gap

Precheck and ShadowRun now have different transform semantics by design:

| Area | Precheck today | ShadowRun today |
| --- | --- | --- |
| Runtime | No runtime call | Calls `TransformRuntimeClient` |
| Data source access | Schema metadata only | Samples real source rows |
| Mapping validation | Checks mapped fields exist in schema metadata | Sends sampled rows through transform runtime |
| Severity behavior | Does not normalize severity | Uses runtime normalization and errors |
| `occurredAt` behavior | Checks required inputs / cursor metadata | Uses runtime draft result |
| Dedup behavior | Validates dedup field presence / stable plan metadata | Uses runtime draft `dedupKey` |
| Duplicate in sample | Not evaluated | Evaluated from runtime `dedupKey` |
| Remote/fallback dependency | None | Follows configured `TransformRuntimeClient` mode |
| Failure scope | Report only | Shadow run only |

This gap is acceptable for the current stage because Precheck is a lightweight gate and ShadowRun is the first real runtime validation step.

The key question is whether Precheck should remain a schema guard or become a runtime validation step.

## Option A: Keep Precheck Dry-Run

Description:

```text
Keep IngestionPlanPrecheckService as schema metadata validation only.
Do not inject or call TransformRuntimeClient.
```

Benefits:

- Lowest implementation risk.
- Keeps Precheck fast and deterministic.
- Does not depend on source row sampling.
- Does not depend on remote/fallback runtime availability.
- Preserves current activation / ShadowRun flow.
- Keeps `TransformRuntimeDependencyGuardTest` unchanged.

Risks:

- Precheck cannot catch runtime-only transform failures.
- Precheck `standardEventPreview` can differ from runtime-normalized ShadowRun preview.
- Invalid severity, invalid occurredAt, or runtime-specific dedup behavior is only caught during ShadowRun.
- Less useful as a readiness signal for eventual dependency removal.

Prerequisites:

- None beyond keeping current tests and guard.

Activation gate impact:

- No behavior change.
- Activation still depends on latest Shadow Run status, not Precheck alone.

Remote/fallback availability impact:

- None. Precheck remains independent of remote/fallback runtime availability.

`edsp-transform-contract` DTO impact:

- None.

`edsp-transform-service` HTTP API impact:

- None.

Error sanitization impact:

- Current Precheck errors remain metadata-oriented and do not expose sampled raw rows.

Dependency removal impact:

- Does not advance Precheck toward runtime parity.
- Keeps one validation surface outside `TransformRuntimeClient`, which should be considered before removing `edsp-core -> edsp-transform`.

Recommendation:

```text
Recommended for now.
```

## Option B: Precheck Uses Real TransformRuntimeClient

Description:

```text
Precheck samples source rows and calls TransformRuntimeClient as part of validation.
```

Benefits:

- Strongest semantic alignment with formal sync and ShadowRun.
- Can catch runtime-only transform errors earlier.
- Can validate severity, occurredAt, dedupKey, and transform errors using the same runtime path.
- Provides stronger evidence for future dependency removal.

Risks:

- Larger behavior change.
- Precheck becomes dependent on source row sampling and runtime availability.
- Remote mode could make Precheck depend on `edsp-transform-service`.
- Fallback mode would need clear local fallback semantics.
- Error handling and report shape must be redesigned to avoid leaking raw rows, source config, JDBC password, tokens, cookies, or secret-like content.
- Could blur the boundary between Precheck and ShadowRun.
- Requires changing `TransformRuntimeDependencyGuardTest`, which currently protects Precheck from runtime access.

Prerequisites:

- Explicit decision that Precheck should be a runtime validation step.
- Clear failure semantics for runtime unavailable, non-2xx, invalid response, timeout, and local transform failure.
- Clear sampling strategy and limits.
- Error sanitization rules equivalent to or stricter than ShadowRun.
- Tests proving no writes to `raw_events`, `standard_events`, `alert_decisions`, or `alerts`.
- Tests proving activation gate semantics do not change.
- Decision on whether Precheck should follow `runtime-mode=remote/fallback` or force local.

Activation gate impact:

- Potentially high.
- If Precheck result changes from metadata-only to runtime-aware, some plans may become blocked before ShadowRun.
- Must not allow Precheck alone to activate a plan.
- Must not weaken the existing requirement that latest Shadow Run must pass before activation.

Remote/fallback availability impact:

- High.
- In `remote` mode, Precheck could fail because transform service is unavailable.
- In `fallback` mode, Precheck could succeed via local fallback, which may hide remote runtime instability unless report semantics are explicit.
- This requires a policy decision before implementation.

`edsp-transform-contract` DTO impact:

- Unknown until implementation design.
- Existing contract can represent batch transform request / response used by ShadowRun, but Precheck-specific summary or validation fields might tempt DTO expansion.
- This assessment does not modify contract DTOs.
- If future implementation needs new DTO fields, that must be a separate scope decision.

`edsp-transform-service` HTTP API impact:

- Should remain unchanged if Precheck reuses the existing batch API.
- If new endpoint or response fields are needed, that is out of scope and must be separately approved.

Error sanitization impact:

- High.
- Precheck would handle real sampled data and runtime errors, so it must not expose:
  - JDBC password.
  - Source config.
  - Complete raw row.
  - `data_sources.config_json`.
  - Payload / raw / detail values.
  - Token / password / credential / authorization / cookie values.

Dependency removal impact:

- Positive if implemented safely, because Precheck would move closer to `TransformRuntimeClient` semantics.
- But it also increases reliance on runtime availability and guard changes, so it should not be the first implementation step without a narrower design.

Recommendation:

```text
Not recommended as an immediate implementation.
```

## Option C: Two-Stage Precheck

Description:

```text
Keep existing schema guard as stage 1.
Add an optional runtime validation stage after schema guard passes.
```

This is a future implementation candidate only. It is not implemented in this stage.

Benefits:

- Preserves the current fast metadata guard.
- Allows runtime validation to be opt-in or separately controlled.
- Makes failure semantics easier to separate:
  - schema blockers
  - runtime validation failures
- Gives a migration path toward stronger runtime parity without replacing the existing Precheck in one step.
- Can keep activation gate unchanged while improving operator visibility.

Risks:

- More report complexity.
- More tests needed.
- Must avoid creating a second ShadowRun.
- Must avoid duplicating transform business logic in Precheck.
- Must decide whether runtime validation follows `local`, `remote`, or `fallback` mode.
- Requires explicit update to dependency guard if Precheck intentionally uses `TransformRuntimeClient`.

Prerequisites:

- A dedicated `Precheck Real Runtime Alignment Implementation MVP`.
- Defined report shape for schema result and runtime result.
- Defined runtime failure policy.
- Defined config / trigger behavior for optional runtime validation.
- Error sanitization rules.
- Tests for no business-table writes.
- Tests for activation gate unchanged.
- Tests for `remote` / `fallback` availability semantics.

Activation gate impact:

- Should remain unchanged.
- Activation must still require latest Shadow Run status `passed`.
- Runtime validation failures in Precheck should not activate or deactivate a plan.
- If runtime validation is optional, failed runtime validation should not silently bypass existing schema blockers.

Remote/fallback availability impact:

- Medium to High, depending on policy.
- If optional, runtime unavailability can be reported without blocking schema guard.
- If required, runtime unavailability can make Precheck stricter than today.
- Must explicitly define whether fallback success should be considered acceptable or flagged.

`edsp-transform-contract` DTO impact:

- Prefer no DTO change by reusing existing batch request / response.
- If Precheck needs fields not represented by current contract, stop and plan a separate contract/API stage.

`edsp-transform-service` HTTP API impact:

- Prefer no API change by reusing `POST /api/transform/standard-events/batch`.
- New endpoint or schema change should be out of scope for the implementation MVP unless explicitly approved.

Error sanitization impact:

- Must be explicit.
- Runtime validation output must use safe summaries and previews only.
- Do not persist or return full source rows, source configs, DB dumps, or secret-like values.

Dependency removal impact:

- Positive. Option C can make Precheck semantics closer to formal sync while preserving a safe migration path.
- It can reduce uncertainty before removing `edsp-core -> edsp-transform`.

Recommendation:

```text
Best candidate for a future implementation stage, but not implemented now.
```

## Decision Matrix

| Option | Benefits | Risks | Activation gate impact | Remote/fallback impact | Contract/API impact | Dependency removal impact | Recommendation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Option A: Keep dry-run | Stable, fast, no runtime dependency | Runtime-only errors remain ShadowRun-only | None | None | None | Limited progress | Keep now |
| Option B: Direct real runtime | Strongest runtime parity | Broad behavior change, runtime availability risk | Potentially high | High | Prefer none, but risk of pressure to expand | Positive but risky | Do not implement now |
| Option C: Two-stage Precheck | Balanced migration path | More report and test complexity | Should remain unchanged if designed correctly | Medium to High, policy-dependent | Prefer no change | Positive | Preferred future candidate |

## Implementation Guardrails For Any Future Stage

Any future Precheck real runtime implementation must:

- Be a separate implementation MVP.
- Keep activation gate unchanged.
- Keep ShadowRun behavior unchanged unless explicitly scoped.
- Keep sync once / scheduled sync behavior unchanged.
- Avoid writes to `raw_events`, `standard_events`, `alert_decisions`, and `alerts`.
- Avoid changing `edsp-transform-contract` DTOs unless explicitly approved.
- Avoid changing `edsp-transform-service` HTTP API unless explicitly approved.
- Avoid deleting `edsp-core -> edsp-transform`.
- Avoid copying `StandardEventTransformService` logic into Precheck.
- Avoid adding `StandardEventTransformService` or `com.edsp.transform.standardevent.*` imports to Precheck.
- Update `TransformRuntimeDependencyGuardTest` intentionally if Precheck is allowed to use `TransformRuntimeClient`.
- Provide tests for runtime unavailable, non-2xx, invalid response, timeout, fallback behavior, sanitization, and no business-table writes.

## Current Recommendation

Recommended now:

```text
Keep Precheck dry-run / schema metadata validation.
Do not implement real runtime Precheck in this stage.
```

Recommended future implementation route:

```text
Option C: Two-stage Precheck.
```

Required future stage:

```text
Precheck Real Runtime Alignment Implementation MVP
```

Reasoning:

- ShadowRun is already the real runtime validation path.
- Precheck currently acts as a fast metadata guard before ShadowRun.
- Directly upgrading Precheck to real runtime would widen runtime availability, sanitization, report, and activation-gate risk.
- Option C keeps the existing schema guard and allows runtime validation to be added deliberately, with clear failure semantics.

## Explicit Non-Goals

This assessment does not:

- Inject `TransformRuntimeClient` into `IngestionPlanPrecheckService`.
- Call `TransformRuntimeClient`.
- Call remote transform service.
- Change activation gate.
- Change ShadowRun.
- Change sync once / scheduled sync.
- Change runtime mode defaults.
- Change `edsp-transform-contract` DTOs.
- Change `edsp-transform-service` HTTP API.
- Delete `edsp-core -> edsp-transform`.
- Add Gateway / Nacos / metrics / structured logging / tracing.
- Add migrations.
- Modify frontend.
- Modify Docker Compose.
- Modify GitHub Actions.
- Modify runtime smoke scripts.
