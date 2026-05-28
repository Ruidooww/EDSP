# Transform Local Dependency Removal Readiness MVP

## Summary

This document records the readiness assessment for removing the direct Maven dependency:

```text
edsp-core -> edsp-transform
```

This stage is assessment-only. It does not remove `edsp-core -> edsp-transform`, does not change production Java code, and does not change runtime behavior.

The current recommendation is to keep the transitional dependency for now, keep the strict bridge guard, and align Shadow/Precheck transform semantics before attempting removal.

## Current Dependency State

Current Maven dependency state:

```text
edsp-core -> edsp-transform
edsp-core -> edsp-transform-contract
edsp-core does not depend on edsp-transform-service
```

Evidence:

- `backend/edsp-core/pom.xml` declares `edsp-transform`.
- `backend/edsp-core/pom.xml` declares `edsp-transform-contract`.
- `backend/edsp-core/pom.xml` does not declare `edsp-transform-service`.
- `TransformRuntimeDependencyGuardTest` explicitly checks that `edsp-core` does not depend on the `edsp-transform-service` Maven module.

The remaining `edsp-core -> edsp-transform` dependency exists because `local` and `fallback` runtime modes still need the local transform engine.

Current runtime mode usage:

- `local`: uses `StandardEventTransformService` through `LocalTransformRuntimeClient`.
- `remote`: uses `RemoteTransformRuntimeClient` and does not need local engine for the primary transform path.
- `fallback`: uses remote first, then local transform through `LocalTransformRuntimeClient` if remote fails.

## Current Allowed Engine Bridge

Current explicit allowlist for transform engine references in `edsp-core` main code:

```text
backend/edsp-core/src/main/java/com/edsp/core/config/TransformConfig.java
backend/edsp-core/src/main/java/com/edsp/core/config/TransformRuntimeConfig.java
backend/edsp-core/src/main/java/com/edsp/core/transform/runtime/LocalTransformRuntimeClient.java
backend/edsp-core/src/main/java/com/edsp/core/transform/runtime/TransformContractSupport.java
```

Current bridge responsibilities:

- `TransformConfig.java` creates the local `StandardEventTransformService` bean.
- `TransformRuntimeConfig.java` wires `local`, `remote`, and `fallback` runtime clients.
- `LocalTransformRuntimeClient.java` adapts contract DTO requests to local transform engine calls.
- `TransformContractSupport.java` converts between `edsp-transform-contract` DTOs and local engine model types.

Business entrypoints must not directly reference:

```text
StandardEventTransformService
com.edsp.transform.standardevent.*
```

Current inventory shows `IngestionPlanSyncOnceService` depends on:

```text
TransformRuntimeClient
BatchTransformRequest
TransformMappingPlanDto
TransformOptionsDto
TransformResponse
```

It does not directly import `StandardEventTransformService` or `com.edsp.transform.standardevent.*`.

## Current Guard Coverage

`TransformRuntimeDependencyGuardTest` currently covers:

- Scans `backend/edsp-core/src/main/java/com/edsp/core`.
- Fails if main code references `com.edsp.transform.standardevent`.
- Fails if main code references `StandardEventTransformService`.
- Allows only explicit bridge files listed above.
- Fails if `edsp-core` main code references transform-service Java packages.
- Fails if `backend/edsp-core/pom.xml` declares `edsp-transform-service`.
- Does not scan test code, so test fixtures can instantiate local transform clients where needed.

Current guard strength:

- It is intentionally file-specific.
- It does not allow the whole `transform/runtime/**` directory.
- A new bridge file must be explicitly added to the allowlist.

Known limitation:

- The guard protects Java source and the `edsp-core` Maven dependency on `edsp-transform-service`. It does not attempt to remove the allowed local engine bridge yet.

## Why Direct Removal Is Not Ready Yet

Directly removing `edsp-core -> edsp-transform` is not ready because:

1. `local` runtime mode still requires the local transform engine.
2. `fallback` runtime mode still requires the local transform engine after remote failure.
3. `LocalTransformRuntimeClient` still calls `StandardEventTransformService`.
4. `TransformContractSupport` still converts contract DTOs to local engine model types.
5. `remote` is not the default runtime mode.
6. `fallback` remains an explicit supported runtime mode.
7. ShadowRun and Precheck still use their own validation and preview logic, not the same `TransformRuntimeClient` path as formal sync.
8. Remote/fallback runtime smoke has been validated, but the PR check is still non-required and should collect more stability evidence before becoming a stronger gate.
9. A rollback strategy for remote-only operation is not yet defined.

If `edsp-core -> edsp-transform` were removed now, the following would break:

- `TransformConfig.java` local transform bean creation.
- `TransformRuntimeConfig.java` local and fallback wiring.
- `LocalTransformRuntimeClient.java`.
- `TransformContractSupport.java`.
- Tests that instantiate `LocalTransformRuntimeClient(new StandardEventTransformService())`.
- Local runtime mode.
- Fallback runtime mode.

## Removal Options

### Option A: Remote-only core

Description:

```text
edsp-core completely removes edsp-transform and only calls edsp-transform-service through HTTP.
```

Pros:

- Cleanest service boundary.
- Removes transform engine classes from `edsp-core`.
- Makes `edsp-transform-service` the only transform execution owner.

Cons:

- Removes local mode unless a separate fallback design exists.
- Removes current local fallback path.
- Makes sync correctness depend on transform-service availability in more environments.
- Requires stronger runtime operations and rollback strategy.

Risk:

- High today. A transform-service outage would have a wider blast radius if fallback is removed.

Prerequisites:

- Remote runtime stability evidence from multiple PR/runtime smoke runs.
- Clear decision to remove or replace fallback.
- ShadowRun/Precheck alignment or an explicit decision that they do not depend on local engine semantics.
- Clear rollback plan.
- Stronger observability and failure triage.

Recommendation:

```text
Not recommended now.
```

Next step:

```text
Do not implement remote-only core until local/fallback policy and validation semantics are settled.
```

### Option B: Local bridge module

Description:

```text
Move the local transform bridge out of edsp-core into a separate adapter module.
edsp-core no longer depends directly on edsp-transform.
edsp-core depends on a bridge module that depends on edsp-transform.
```

Pros:

- Removes direct `edsp-core -> edsp-transform` dependency.
- Preserves local and fallback runtime behavior.
- Keeps engine usage isolated behind a smaller module boundary.
- Can be a stepping stone before remote-only core.

Cons:

- Adds another module and boundary to maintain.
- Does not remove local engine from the overall runtime.
- Still keeps transform engine reachable through a bridge.
- Needs careful dependency guard updates to avoid moving the coupling without reducing it.

Risk:

- Medium. It is safer than remote-only core but adds module complexity.

Prerequisites:

- Define bridge module ownership and package names.
- Move `LocalTransformRuntimeClient` and `TransformContractSupport` or equivalent bridge code into the new module.
- Update Spring wiring without changing runtime semantics.
- Add module-level dependency guard.
- Verify local, remote, and fallback modes after the move.

Recommendation:

```text
Possible later, but not the first removal step.
```

Next step:

```text
Consider after Shadow/Precheck Alignment if local/fallback must remain long term.
```

### Option C: Keep transitional dependency with strict guard

Description:

```text
Keep edsp-core -> edsp-transform temporarily.
Continue to enforce that only explicit bridge files may reference the transform engine.
```

Pros:

- Preserves current local and fallback runtime behavior.
- Avoids destabilizing sync while remote runtime evidence is still accumulating.
- Keeps business services protected by `TransformRuntimeDependencyGuardTest`.
- Avoids module churn before Shadow/Precheck semantics are aligned.

Cons:

- `edsp-core` still has a compile-time dependency on the local transform engine.
- The system is not fully service-separated yet.
- Removal remains future work.

Risk:

- Low today, because direct engine references are already restricted to explicit bridge files.

Prerequisites:

- Keep guard allowlist explicit.
- Do not add broad directory allowlists.
- Review any new bridge file before adding it to the allowlist.
- Continue collecting non-required PR check evidence.

Recommendation:

```text
Recommended now.
```

Next step:

```text
Keep the transitional dependency and prioritize Standard Event Transform Shadow/Precheck Alignment MVP.
```

## Decision Matrix

| Option | Pros | Cons | Risk | Recommendation | Next step |
| --- | --- | --- | --- | --- | --- |
| Option A: Remote-only core | Cleanest boundary; removes engine from core | Removes local/fallback unless replaced; needs strong runtime operations | High | Not recommended now | Revisit after remote runtime has enough stability evidence and rollback strategy |
| Option B: Local bridge module | Removes direct core dependency while preserving local/fallback | Adds module complexity; still keeps local engine in runtime | Medium | Possible later | Revisit after Shadow/Precheck Alignment if local/fallback remains necessary |
| Option C: Keep transitional dependency with strict guard | Preserves runtime behavior and blocks business-layer coupling | Direct dependency remains | Low | Recommended now | Keep guard strict and align Shadow/Precheck next |

## Next Step Criteria

### Enter true Local Dependency Removal MVP when

All of the following are true:

1. Remote runtime has enough stability evidence from non-required PR check or runtime smoke runs.
2. The team has made an explicit decision on whether fallback remains required.
3. ShadowRun and Precheck transform semantics are aligned with formal sync, or explicitly decoupled from local engine semantics.
4. The local bridge target is clear: remote-only removal or a separate local bridge module.
5. Rollback strategy is documented.
6. `TransformRuntimeDependencyGuardTest` or its successor can still prevent business-layer engine references.
7. Artifact/log safety boundaries remain acceptable.

### Keep transitional dependency when

Any of the following are true:

1. Local or fallback runtime remains necessary.
2. Remote-only is not the default or recommended production path.
3. Business-layer code is already guarded from direct transform engine usage.
4. Shadow/Precheck validation still has separate transform-like semantics.
5. Runtime smoke PR check has not yet produced enough stable samples.

## Recommended Next Stage

Recommended next stage:

```text
Standard Event Transform Shadow/Precheck Alignment MVP
```

Rationale:

- ShadowRun and Precheck still evaluate mapping, severity, dedup, and preview behavior outside the formal sync `TransformRuntimeClient` path.
- Removing `edsp-core -> edsp-transform` before aligning validation paths would leave unclear transform semantics across sync, shadow, and precheck.
- Alignment will clarify whether ShadowRun/Precheck should use contract DTOs, `TransformRuntimeClient`, remote shadow, or an explicitly separate validation policy.

Current recommendation:

```text
Do not remove edsp-core -> edsp-transform now.
Keep Option C until Shadow/Precheck Alignment is complete or remote-only strategy is explicitly accepted.
```

