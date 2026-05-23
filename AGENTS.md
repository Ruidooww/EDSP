# EDSP Agent Execution Rules

## Project Workflow

This project follows staged MVP delivery.

Core rule:

```text
One stage.
One branch.
One clear scope.
One merge.
One HANDOFF update.
Clean master before the next stage.
```

Agents must not expand the current stage scope without explicit user instruction.

## Agent Execution Mode

When executing development tasks, use available agent capabilities actively and safely.

Rules:

```text
- Follow the existing stage plan and do not expand scope without explicit user instruction.
- Make an implementation plan before changing code when the task is non-trivial.
- Check key boundaries before implementation, during implementation, and before merge.
- If there are two or more independent tasks with low file-conflict risk, dispatch them to separate sub-agents in parallel when the environment supports it.
- If a task has a clear implementation plan and can be split safely, use multiple sub-agents with clear ownership.
- Do not assign multiple sub-agents to modify the same files at the same time unless explicitly coordinated.
- One agent must own final integration and conflict resolution.
- Before marking the task complete or before merge, request a code review focused on:
  - bugs
  - scope drift
  - missing tests
  - state boundary violations
  - accidental writes to forbidden tables
  - frontend permission/button visibility issues
  - migration compatibility
- Do not skip tests because sub-agents completed their parts.
- Final verification must still be run from the integrated branch.
```

## Branch Rules

Every new stage must start from a clean `master`.

Before starting a new stage:

```powershell
git checkout master
git pull --ff-only origin master
git status --short --branch
```

Only continue if the working tree is clean.

Do not develop feature code directly on `master`.

Create a dedicated branch for each stage:

```powershell
git checkout -b codex/<stage-name>
```

Examples:

```text
codex/ingestion-plan-sync-once
codex/scheduled-sync-mvp
codex/rule-decision-mvp
codex/alerts-mvp
```

## Scope Rules

Each stage must implement only its declared scope.

Do not mix unrelated capabilities in one stage.

Forbidden unless explicitly instructed:

```text
- Do not combine collection, rules, alerts, notifications, and AI in one stage.
- Do not introduce Kafka / Redis / ClickHouse unless explicitly planned.
- Do not add AI / XGBoost / Agent orchestration unless explicitly planned.
- Do not add new status values or database tables outside the stage plan.
- Do not bypass raw_events / standard_events to generate alerts directly.
```

## Verification Before Merge

Before merging any stage branch into `master`, run:

```powershell
cd backend
mvn -pl edsp-core -am test

cd ..\frontend
npm.cmd run build

cd ..
git diff --check
git status --short --branch
```

Requirements:

```text
- Backend tests must pass.
- Frontend build must pass.
- git diff --check must report no whitespace errors.
- Working tree must be clean.
```

Line-ending warnings caused by Git `autocrlf` are not blocking if no whitespace error is reported.

## Review And Merge Authorization Rules

Before marking a stage complete, and before any merge into `master`, the agent must request code review.

The review must focus on bugs, scope drift, missing tests, state boundary violations, accidental writes to forbidden tables, frontend permission/button visibility issues, migration compatibility, idempotency problems, unintended background execution, and stale or inaccurate handoff notes.

The agent must report review findings using this severity model:

```text
- P0: must fix before merge
- P1: should fix before merge
- P2: can be tracked after merge
```

A stage with unresolved P0 or P1 must not be merged.

Passing tests does not equal review approval.

Agents must not merge a stage branch into `master` unless the user explicitly approves the merge after review.

This rule is mandatory.

Allowed before explicit user merge approval:

```text
- implement code
- add tests
- update files inside the stage branch
- run verification commands
- push the stage branch
- report test/build results
- request review
- propose merge commands
```

Forbidden before explicit user merge approval:

```text
- do not run `git merge`
- do not push to `master`
- do not run `git push origin master`
- do not update `HANDOFF.md` on `master`
- do not create a post-merge docs commit
- do not treat passing tests as approval
- do not self-approve code review
- do not assume "looks good" means merge approval unless the user explicitly says to merge
```

Explicit merge approval means the user says something clearly equivalent to:

```text
- 可以合并
- 合并吧
- merge it
- approved to merge
- 可以 merge 到 master
```

If the user only says something like:

```text
- 看一下
- 审查一下
- 测试通过了
- 我提交了
- 我 push 了
- 继续
- 下一步
```

that is not merge approval.

In those cases, the agent must stop after review and report whether the branch is ready to merge.

After implementation and verification, the agent must stop and report:

```text
- Stage branch
- Latest branch commit
- Changed files
- Tests run
- Frontend build result
- git diff --check result
- git status result
- Known risks
- Review result
- Merge recommendation
```

Then the agent must wait for explicit user approval.

Do not merge automatically.

Only after the user explicitly approves merge may the agent run:

```powershell
git checkout master
git pull --ff-only origin master
git merge --no-ff <stage-branch-name> -m "merge: <stage name>"
```

## Merge Rules

After verification passes, merge the stage branch into `master` using `--no-ff`:

```powershell
git checkout master
git pull --ff-only origin master

git merge --no-ff codex/<stage-branch> -m "merge: <stage-name>"
```

After merge, record the feature merge commit hash:

```powershell
git log --oneline -1
```

Important:

```text
"Latest merge commit" means the feature merge commit, not the later HANDOFF docs commit.
```

Then update `HANDOFF.md` on `master`.

Only `HANDOFF.md` may be modified, staged, and committed in this post-merge docs step.

Commit:

```powershell
git add HANDOFF.md
git commit -m "docs: update handoff for <stage-name>"
git push origin master
```

This docs-only commit on `master` is allowed because it records stage closure, not feature development.

After the docs commit, `master` will normally have two commits ahead of the stage branch:

```text
1. the merge commit
2. the HANDOFF docs commit
```

Therefore, after `--no-ff` merge plus post-merge HANDOFF docs commit, this may be normal:

```text
git rev-list --left-right --count origin/master...origin/<stage-branch>
2       0
```

The important code-content check is:

```powershell
git diff --stat origin/<stage-branch>..origin/master -- . ':!HANDOFF.md'
```

Expected output:

```text
<no output>
```

This means `master` has no code-content difference from the stage branch except the post-merge `HANDOFF.md` update.

## Final Verification After Push

After pushing `master`, run:

```powershell
git fetch origin
git status --short --branch
git log --oneline -3 origin/master
git diff --stat origin/<stage-branch>..origin/master -- . ':!HANDOFF.md'
git rev-list --left-right --count origin/master...origin/<stage-branch>
```

Expected:

```text
git diff --stat origin/<stage-branch>..origin/master -- . ':!HANDOFF.md'
```

must have no output.

If the stage branch was merged with `--no-ff` and `HANDOFF.md` was updated after merge, this is normal:

```text
git rev-list --left-right --count origin/master...origin/<stage-branch>
2       0
```

Meaning:

```text
master has the feature merge commit plus the HANDOFF docs commit.
The stage branch has no code missing from master.
```

## HANDOFF Rules

`HANDOFF.md` must be updated after each completed stage is merged into `master`.

Do not update `HANDOFF.md` for every small commit.

Update `HANDOFF.md` when:

```text
- A stage branch is merged into master.
- The system boundary changes.
- The next stage direction changes.
- A key table / API / status flow is added.
- A major P0 / P1 issue is fixed.
```

`HANDOFF.md` should record stage-level conclusions, not chat history.

It should include:

```text
- Current stable branch.
- Current stage.
- Latest feature merge commit.
- Latest HANDOFF docs commit, if already created.
- Completed capabilities.
- Explicitly not implemented items.
- Current hard boundaries.
- Test results.
- Known follow-up items.
- Recommended next stage.
```

Recommended commit fields:

```text
Latest feature merge commit:
Latest HANDOFF docs commit:
```

Do not turn `HANDOFF.md` into a chat log.

## Current Completed Stages

The following stages have already been completed and merged into `master`:

```text
Database Intelligence Ingestion Plan MVP
Shadow Validator MVP
Ingestion Plan Quality Hardening
Ingestion Plan Activation Gate MVP
```

Latest known stable feature merge after Activation Gate:

```text
47e303c merge: ingestion plan activation gate
```

If a post-merge HANDOFF docs commit has been created after this merge, update both fields in `HANDOFF.md`:

```text
Latest feature merge commit: 47e303c merge: ingestion plan activation gate
Latest HANDOFF docs commit: <docs commit hash> docs: update handoff for ingestion plan activation gate
```

Current `master` is the stable baseline.

## Current Hard Boundaries

These constraints must not be violated:

```text
1. Do not bypass raw_events / standard_events to generate alerts directly.
2. Do not activate an ingestion plan unless the latest Shadow Run status is passed.
3. Do not execute formal sync unless there is an active activation.
4. Do not allow warning / failed / blocked Shadow Run to activate a plan.
5. Activation must not modify ingestion_plans.status.
6. Sync Once MVP must not generate alert_decisions or alerts.
7. Do not trigger notifications before Notification MVP.
8. Do not combine collection, rules, alerts, notifications, and AI in one stage.
```

## Activation Gate Boundary

Activation Gate means:

```text
Activation is an audit gate record.
Activation is not formal data collection.
Activation is not scheduled sync.
Activation is not alert generation.
```

Activation Gate rules:

```text
- Only approved / shadow_ready ingestion plans can be activated.
- The latest Shadow Run must be passed.
- shadowRunId must belong to the current ingestion plan.
- shadowRunId must be the latest Shadow Run for that plan.
- One plan cannot have multiple active activations.
- Deactivate only updates ingestion_plan_activations.
- Deactivate must not modify ingestion_plans.status.
```

Activation Gate must not:

```text
- write raw_events
- write standard_events
- write alert_decisions
- write alerts
- trigger notifications
- add ingestion_plans.status = active
```

## Next Recommended Stage

The next recommended stage is:

```text
Ingestion Plan Sync Once MVP
```

Goal:

```text
active activation
then manual sync once
then read source data
then write raw_events
then write standard_events
then record sync / ingestion run
then do not generate alerts
```

## Sync Once MVP Scope

Sync Once MVP may implement:

```text
- active activation validation
- manual sync-once API
- JDBC source row reading
- raw_events writing
- standard_events writing
- sync run / ingestion run recording
- dedup_key idempotency
- partial row failure handling
- frontend sync result display
```

Sync Once MVP must not implement:

```text
- scheduled sync
- rule engine
- alert_decisions
- alerts
- notifications
- AI
- XGBoost
- Agent orchestration
- Kafka
- Redis
- ClickHouse
```

## Sync Once MVP Acceptance Criteria

Sync Once MVP is complete only if:

```text
1. Only active activation can execute sync-once.
2. Deactivated or missing activation cannot execute sync-once.
3. Backend can read real JDBC source rows.
4. raw_events are written.
5. standard_events are written.
6. sync run / ingestion run is recorded.
7. Re-running the same source rows does not duplicate standard_events.
8. Partial row failures produce warning, not total failure.
9. No alert_decisions are created.
10. No alerts are created.
11. Frontend can display sync result.
```

## Final Rule

For every future stage:

```text
One stage.
One branch.
One scope.
One full verification.
One merge.
One post-merge HANDOFF update.
Clean master before continuing.
```
