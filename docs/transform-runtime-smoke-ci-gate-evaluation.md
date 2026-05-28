# Transform Runtime Smoke Auto CI Gate Evaluation MVP

## Summary

This document evaluates whether `Transform Runtime Smoke` should move from a manual-only workflow to an automatic CI gate.

This stage is evaluation-only:

- It does not implement an automatic CI gate.
- It does not add `push`, `pull_request`, or `schedule` triggers.
- It does not set a required check.
- It does not modify the existing EDSP CI workflow.
- It does not modify runtime smoke scripts or production runtime behavior.

## Current State

The manual workflow already exists:

```text
.github/workflows/transform-runtime-smoke.yml
```

Current workflow state:

- Trigger: `workflow_dispatch`.
- Existing EDSP CI is unchanged.
- The workflow is not attached to `push`.
- The workflow is not attached to `pull_request`.
- The workflow is not a required check.
- Runtime mode defaults remain unchanged.
- Production runtime behavior is unchanged.

Post-merge manual run result:

| Field | Value |
| --- | --- |
| Workflow | `Transform Runtime Smoke` |
| Run | `#1` |
| Run URL | `https://github.com/Ruidooww/EDSP/actions/runs/26549739874` |
| Job URL | `https://github.com/Ruidooww/EDSP/actions/runs/26549739874/job/78209198316` |
| Branch | `master` |
| Commit | `6266964` |
| Status | `Success` |
| Duration | `3m21s` |
| Artifact | `transform-runtime-smoke-26549739874-1` |
| Artifact content | `summary.json` only |

`summary.json` result:

```text
remoteSuccess=PASS
remoteUnavailable=PASS
fallbackUnavailable=PASS
transformRuntimeVerification=PASS
failureStage=null
failureType=null
failureMessage=null
warnings=[]
```

## Current Workflow Boundaries

The current workflow is intentionally manual-only:

- No `push` trigger.
- No `pull_request` trigger.
- No `schedule` trigger.
- No required check.
- No existing EDSP CI modification.
- No runtime-mode default change.
- No production runtime behavior change.
- No destructive cleanup.

## Risk Matrix

| Risk | Impact | Likelihood | Mitigation | Current Recommendation |
| --- | --- | --- | --- | --- |
| Docker Compose build/start cost | Medium: slower feedback than unit tests | Medium | Keep manual-only until more timing samples exist | Do not make required yet |
| GitHub-hosted runner resource variance | Medium: CPU, disk, or Docker startup variance can cause noise | Medium | Collect more runs before gating | Use non-required PR check first if automating |
| Maven dependency download network variance | Medium: cold downloads or transient network failures may fail unrelated changes | Medium | Preserve logs and retry only in a separate implementation stage | Do not attach to `push` now |
| Docker network or host port conflict | Medium: runtime smoke depends on Docker networking and host ports | Low to Medium | Continue fixed CI ports only while runner is isolated; document port assumptions | Validate in non-required mode before required |
| Runtime smoke flake blocks PRs | High if required | Unknown: only one manual run sample exists | Require 3-5 stable non-required runs first | Do not set required check now |
| Artifact or log leakage | High if raw data or env leaks | Low with current summary-only success artifact | Keep artifact limited to `summary.json` and project-scoped logs on failure | Retain current artifact policy |
| `actions/upload-artifact@v4` Node.js 20 warning | Low: current workflow still succeeds | High: warning is visible now | Track as P2; wait for official action or runner migration unless it becomes blocking | Do not modify workflow in this stage |
| Required check slows mainline | High: every PR would wait for full compose smoke | Medium | Start with manual-only or non-required PR check | Required gate is not recommended now |

## Decision Matrix

| Option | Pros | Cons | Recommendation | Next step |
| --- | --- | --- | --- | --- |
| Keep manual-only workflow | Lowest risk; already proven once on `master`; no mainline impact | Requires manual trigger for runtime confidence | Recommended now | Continue using `workflow_dispatch` for release or risky runtime changes |
| Add `push` trigger | Broad automatic coverage | High noise and cost; runs on every push; not targeted to review risk | Not recommended now | Reconsider only after PR check is stable |
| Add `pull_request` trigger as non-required check | Observes PR runtime health without blocking merges | Adds runtime cost; still can be flaky | Recommended next if continuing CI work | Implement `Transform Runtime Smoke Non-Required PR Check MVP` |
| Add required check | Strongest protection against runtime regressions | Blocks merges on runtime flakes; only one success sample exists | Not recommended now | Consider only after 3-5 stable non-required runs |
| Move to Shadow/Precheck Alignment instead of CI gate work | Improves transform consistency across validation paths | Does not increase automated runtime coverage | Valid alternative if product correctness is higher priority | Plan `Standard Event Transform Shadow/Precheck Alignment MVP` |
| Move to Transform Local Dependency Removal Readiness instead of CI gate work | Advances service decomposition and boundary cleanup | Does not improve CI signal immediately | Valid alternative if architecture decoupling is higher priority | Plan `Transform Local Dependency Removal Readiness MVP` |

## Trigger Evaluation

### `workflow_dispatch` only

Current recommendation: keep.

`workflow_dispatch` is appropriate now because runtime smoke is heavier than normal unit tests and currently has only one successful GitHub-hosted runner sample. It gives maintainers an explicit verification path without slowing every PR.

### `pull_request` trigger

Current recommendation: candidate for the next stage only as a non-required check.

`pull_request` is a better automatic trigger than `push` because it aligns with review and merge confidence. It should not be required until flake rate, duration, artifact safety, and failure triage are proven over multiple runs.

### `push` trigger

Current recommendation: not recommended now.

`push` would run runtime smoke more often than necessary, including intermediate branch updates and docs-only changes. It increases cost and noise before the runtime smoke signal is proven stable.

## Required Check Evaluation

Current recommendation: do not set a required check.

Reasons:

- The manual workflow has only one successful GitHub-hosted runner run.
- There are not yet 3-5 stable samples across different commits or runner allocations.
- Docker Compose runtime smoke can still be affected by runner resources, Docker startup, network access, Maven dependency downloads, and port behavior.
- If required check flakes, it blocks the mainline even when backend/frontend tests are healthy.
- The current warning from `actions/upload-artifact@v4` is non-blocking but should be observed before adding stricter gating.

## Artifact And Logs Evaluation

Current artifact policy:

- Successful run artifact retention: 7 days.
- Successful run artifact content: `summary.json` only.
- Failure mode can collect limited project-scoped logs.
- Artifact name includes GitHub run ID and attempt.

Artifact policy to keep:

- Do not collect DB dumps.
- Do not collect full raw rows.
- Do not collect `data_sources.config_json`.
- Do not collect full environment output.
- Do not collect secret-like content.
- Continue to prefer aggregate result fields such as `remoteSuccess`, `remoteUnavailable`, `fallbackUnavailable`, and `transformRuntimeVerification`.

If this workflow becomes a PR check later, artifact safety must be reviewed again before enabling automatic triggers.

## Node.js 20 Warning Evaluation

`actions/upload-artifact@v4` currently emits a GitHub platform Node.js 20 deprecation warning.

Current assessment:

- The warning did not affect the successful manual run.
- The warning is not a P0 or P1 issue.
- Current severity: P2.
- This stage does not upgrade the action or modify workflow configuration.
- If GitHub changes runner behavior or the warning becomes blocking, handle it in a separate workflow maintenance stage.

## Next Step Criteria

### Enter Non-Required PR Check MVP when

- The current manual workflow remains available.
- Artifact safety boundaries remain unchanged.
- The new PR check is explicitly non-required.
- Failure does not block merge.
- The team accepts runtime smoke as an observational PR signal.

### Enter Required Gate MVP when

- A non-required PR check has run successfully at least 3-5 times.
- Any flake causes, failure logs, and fix paths are clear.
- Runner resources and total duration remain acceptable.
- The team explicitly accepts runtime smoke as a merge-blocking check.
- Artifact and log safety boundaries are reviewed again.

### Keep manual-only when

- The team does not want Docker Compose smoke to affect PR feedback speed.
- The current stage priority is service decomposition, transform runtime boundaries, or validation consistency.
- Manual workflow is sufficient for release or risky-runtime-change verification.

## Final Recommendation

Recommended now:

```text
Keep manual-only workflow.
```

Recommended next if continuing CI work:

```text
Transform Runtime Smoke Non-Required PR Check MVP.
```

Alternative if prioritizing service decomposition:

```text
Transform Local Dependency Removal Readiness MVP.
```

Alternative if prioritizing validation consistency:

```text
Standard Event Transform Shadow/Precheck Alignment MVP.
```

Not recommended now:

```text
Required check.
```

