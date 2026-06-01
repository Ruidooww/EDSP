# AI Agent Runtime Smoke Observability MVP

## Purpose

This stage verifies the AI agent runtime in a real Docker Compose stack without changing production behavior.

The smoke path is:

`frontend /api`
-> `edsp-gateway`
-> `edsp-core`
-> `ai-agent-service`
-> local OpenAI-compatible mock

The goal is to confirm that the AI agent runtime is safe, observable, and wired end to end:

- provider discovery stays configuration-only
- local OpenAI-compatible runtime succeeds through the real runtime path
- cloud OpenAI-compatible runtime succeeds through the same runtime path
- fallback-template still works when explicitly selected
- recent runs succeed through the frontend-visible API route
- recent runs only persist safe summaries
- runtime artifacts stay summary-only

This stage only adds one minimal controller parameter-binding fix. It does not change frontend behavior, notification delivery, or database schema.

## Smoke Scenarios

The smoke script verifies these scenarios through the real runtime chain:

1. provider discovery through `GET /api/core/ai-agents/providers`
2. `local-openai-compatible` success through a host-local mock OpenAI endpoint
3. `cloud-openai-compatible` success through the same mock endpoint
4. `fallback-template` warning fallback
5. recent runs through `GET /api/core/ai-agents/runs/recent`
6. `ai_agent_runs` persistence with safe summary columns only
7. prompt safety, response safety, and artifact safety

Invalid `providerKey` handling is intentionally not asserted in this smoke.
Known follow-up: AI Agent Foundation Hardening MVP should enforce `invalid providerKey -> 400`.

## Runtime Boundary

The smoke uses `host.docker.internal` to point `ai-agent-service` at a local OpenAI-compatible mock server started by the smoke script.

The mock server returns safe JSON sections only. It does not read the EDSP database and it does not receive secret material in the artifact.

The smoke script may inspect the database for the rows it created, but the artifact itself remains summary-only.

The recent runs API route must succeed. A database query may be used as failure diagnostics, but it must not convert a route failure into a passing smoke result.

## Artifact Boundary

The smoke artifact is stored under:

`logs/ai-agent-runtime-smoke/<runId>/summary.json`

On failure, the artifact may also contain:

`logs/ai-agent-runtime-smoke/<runId>/restricted-diagnostics.json`

The artifact contains only aggregated verification data such as:

- run id
- compose project
- ports
- scenario statuses
- failure stage and failure type
- non-sensitive warnings

Full Docker Compose service logs are not uploaded.

The artifact does not contain:

- raw prompts
- raw model responses
- API keys
- authorization headers
- raw rows
- source config
- full environment variables
- DB dumps
- secret-like content

## CI Status

This stage adds a non-required PR check workflow for the smoke run.

The workflow is intended for PR visibility and manual dispatch, not as a required branch protection gate.

## Known Follow-up

- AI Agent Foundation Hardening MVP should enforce `invalid providerKey -> 400`.
- A later schema-hardening stage may migrate `ai_agent_runs` JSON summary text fields to `JSONB`.
