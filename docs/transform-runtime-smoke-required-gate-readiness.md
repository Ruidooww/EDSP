# Transform Runtime Smoke Required Gate Readiness MVP

## Summary

This document evaluates whether `Transform Runtime Smoke` is ready to become a required gate.

This stage is readiness-only:

- It does not enable a required check.
- It does not modify GitHub branch protection.
- It does not modify repository settings.
- It does not modify `.github/workflows/transform-runtime-smoke.yml`.
- It does not modify the existing EDSP CI workflow.
- It does not change runtime smoke behavior.

Current conclusion:

```text
Continue non-required PR check.
Do not enable required gate yet.
Collect more PR check samples.
```

## Current State

The runtime smoke workflow exists at:

```text
.github/workflows/transform-runtime-smoke.yml
```

Current workflow state:

- `workflow_dispatch` is supported.
- `pull_request` on `master` is supported.
- `push` is not enabled.
- `schedule` is not enabled.
- Required check is not enabled by workflow YAML or any repository settings-as-code in this repo.
- Branch protection was not modified in this stage.
- Existing `.github/workflows/ci.yml` is unchanged.

Note: unauthenticated GitHub branch protection API checks are not sufficient to independently audit repository settings. This stage relies on repository files, prior HANDOFF state, and the explicit no-settings-change scope. If required checks are managed outside the repository UI/API, they must be reviewed manually by the repository owner before any future gate change.

Current smoke command remains:

```text
./scripts/verify-transform-runtime-smoke.ps1 -CiMode -FrontendPort 18120 -TransformPort 18125 -CollectLogsOnFailure -FinalAction Stop -ReadyAttempts 90
```

## Evidence Inventory

Evidence source:

- GitHub Actions REST API for `Ruidooww/EDSP`.
- Workflow: `transform-runtime-smoke.yml`.
- Checked at this stage before enabling any gate.

API result:

```text
total_count=2
pull_request runs found=0
workflow_dispatch runs found=2
```

No non-required PR check samples were found yet. The available runs are manual `workflow_dispatch` runs on `master`, so they are useful smoke evidence but do not satisfy the required gate sample requirement.

### Existing Runs

| Type | PR URL | Run URL | Commit SHA | Result | Duration | Artifact name | Artifact safety confirmation | Flake | Failure reason |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `workflow_dispatch` | N/A | `https://github.com/Ruidooww/EDSP/actions/runs/26577086570` | `235047a11ca8aeb6f2bb9d7b4da8758f7fb2f487` | `success` | `2m10s` | `transform-runtime-smoke-26577086570-1` | Metadata shows one 474-byte artifact. Artifact archive was not downloaded in this unauthenticated pass; treat content confirmation as limited. | No evidence of flake | N/A |
| `workflow_dispatch` | N/A | `https://github.com/Ruidooww/EDSP/actions/runs/26549739874` | `626696470b3d08f523905647463a858da68628fc` | `success` | `3m21s` | `transform-runtime-smoke-26549739874-1` | HANDOFF records artifact contained only `summary.json`; no DB dump, full raw row, `data_sources.config_json`, full env, or secret-like content. | No evidence of flake | N/A |

Manual sample timing:

- Sample count: `2`.
- Average duration: about `2m46s`.
- Slowest duration: `3m21s`.

PR check sample timing:

- Sample count: `0`.
- Average duration: unavailable.
- Slowest duration: unavailable.

Required gate sample requirement:

```text
Not met.
```

Reason:

```text
There are not yet 3-5 stable non-required PR check runs.
```

## Required Gate Criteria

Only recommend a required gate when all of the following are true:

| Criteria | Current status | Notes |
| --- | --- | --- |
| Non-required PR check has at least 3-5 stable runs | Not met | No `pull_request` runs found for this workflow yet |
| Average duration is acceptable | Not proven | Manual samples average about `2m46s`, but PR samples are missing |
| Slowest duration is acceptable | Not proven | Manual max is `3m21s`, but PR samples are missing |
| No unexplained flake | Not proven | No flake in available manual runs, but PR path has no sample |
| Artifact / logs do not leak sensitive data | Partially proven | Prior manual run confirmed `summary.json` only; current pass could not download latest artifact without auth |
| Team explicitly accepts runtime smoke blocking merges | Not established | This stage only evaluates readiness |
| Rollback plan is explicit | Documented below | No branch protection change made |

Current required gate readiness:

```text
Not ready.
```

## Risk Matrix

| Risk | Impact | Likelihood | Mitigation | Current recommendation |
| --- | --- | --- | --- | --- |
| GitHub runner resource variance | Medium to High: Docker Compose runtime smoke may slow or fail under runner pressure | Medium | Observe multiple non-required PR checks across different runner allocations | Continue non-required only |
| Docker Compose startup variance | Medium: service readiness and container startup can add flake | Medium | Keep `ReadyAttempts`, collect failure logs, avoid required gate until samples exist | Do not require yet |
| Maven dependency download variance | Medium: network or cache misses can fail unrelated PRs | Medium | Review failed logs; consider cache strategy in a separate stage if needed | Do not require yet |
| Docker network / port flake | Medium: runtime smoke depends on Docker networking and fixed host ports in CI | Low to Medium | Keep CI ports isolated; verify through PR samples | Continue observation |
| Required check blocks mainline | High: a flaky runtime check would block merge | Medium until proven otherwise | Require 3-5 stable PR samples and team approval before branch protection | Delay required gate |
| Artifact / logs leak sensitive data | High if logs include raw rows, configs, env, or secrets | Low with current success summary, unknown on failures | Keep summary-only success artifact and limited project-scoped failure logs; review artifacts before gating | Do not expand artifact scope |
| `actions/upload-artifact@v4` Node.js 20 warning | Low currently; could become blocking if platform behavior changes | Medium | Track as P2; update action only in a workflow maintenance stage if needed | Not a blocker now |

## Decision Matrix

| Option | Pros | Cons | Recommendation | Next step |
| --- | --- | --- | --- | --- |
| Continue non-required PR check | Keeps runtime signal visible without blocking merges | Does not enforce runtime smoke before merge | Recommended | Collect 3-5 PR check samples |
| Enable required check now | Strongest merge protection | No PR sample evidence; high risk of blocking mainline on unproven runtime smoke | Not recommended | Do not change branch protection |
| Delay required check until more samples | Preserves safety and lets the team observe flake and duration | Requires another observation stage | Recommended | Run `Transform Runtime Smoke PR Check Observation MVP` |
| Keep manual + non-required only | Lowest operational risk | Runtime regressions may still merge if ignored | Acceptable | Use manual workflow for release/risky runtime changes |

## Branch Protection Note

Required checks are normally enforced through GitHub branch protection or repository rulesets, not by changing workflow YAML alone.

This repository currently has no settings-as-code file for branch protection in `.github/`. This stage does not add one.

`Transform Runtime Smoke` is only a candidate required gate. It must not be described as already enforced.

Codex must not modify repository settings without explicit user authorization. If required gating is approved later, use one of these paths:

- User manually configures GitHub UI branch protection / rulesets.
- User explicitly authorizes a separate GitHub settings API stage.
- A future settings-as-code approach is introduced in a dedicated scope.

Before any future required gate change, the team should have:

- `3-5` stable non-required PR check runs.
- Recorded run results, duration, failure reasons, artifacts, and flake status.
- Reviewed runner / Docker Compose / Maven / network variance.
- Rechecked artifact and failure-log safety boundaries.
- Explicitly accepted `Transform Runtime Smoke` as a merge-blocking gate.

This stage does not:

- Modify repository settings.
- Enable a required check.
- Modify branch protection.
- Modify `.github/workflows/transform-runtime-smoke.yml`.
- Modify `.github/workflows/ci.yml`.
- Add or modify `push` / `schedule` triggers.

If a future stage enables branch protection, it should reference the stable `Transform Runtime Smoke` check name only. It should not make opportunistic changes to existing EDSP CI, workflow triggers, runtime smoke scripts, or production runtime behavior.

## Rollback Plan

If a required gate is enabled in a future stage and later flakes:

1. Keep `workflow_dispatch` available for manual runtime verification.
2. Keep the workflow itself available as a non-required observation check if useful.
3. Remove `Transform Runtime Smoke` from branch protection required checks through GitHub UI or an explicitly authorized settings API change.
4. Record the rollback reason, failed run URLs, failure mode, artifact names, and any suspected flake source.
5. Do not use destructive cleanup commands to recover CI state:

```text
Do not run docker compose down -v.
Do not run docker volume rm.
Do not run docker volume prune.
Do not run docker rm as an automated fallback.
```

6. Re-enter observation mode until flake cause, duration, and artifact safety are understood.

## Final Recommendation

Recommended now:

```text
Continue non-required PR check.
Do not enable required gate yet.
Collect more PR check samples.
```

Required gate is not recommended now because:

- There are no `pull_request` samples for `Transform Runtime Smoke` yet.
- The required `3-5` stable non-required PR check runs do not exist.
- Existing successful runs are manual `workflow_dispatch` runs, not PR gate evidence.
- Artifact safety is partially proven, but latest artifact content was not independently downloaded in this unauthenticated pass.
- Branch protection has not been modified and should not be modified without explicit user authorization.

Recommended next stage if continuing CI hardening:

```text
Transform Runtime Smoke PR Check Observation MVP
```

That stage should collect at least `3-5` real PR check runs, including:

- PR URL.
- Run URL.
- Commit SHA.
- Result.
- Duration.
- Artifact name.
- Artifact content safety confirmation.
- Flake status.
- Failure reason, if any.

Only after that evidence exists should the team reconsider a required gate.
