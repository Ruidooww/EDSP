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
- fallback-template still works when a provider is unavailable or missing
- recent runs only persist safe summaries
- runtime artifacts stay summary-only

This stage does not change production Java, frontend behavior, notification delivery, or database schema.

## Smoke Scenarios

The smoke script verifies these scenarios through the real runtime chain:

1. provider discovery through `GET /api/core/ai-agents/providers`
2. `local-openai-compatible` success through a host-local mock OpenAI endpoint
3. `cloud-openai-compatible` success through the same mock endpoint
4. `fallback-template` warning fallback
5. unknown provider fallback behavior
6. recent runs through `GET /api/core/ai-agents/runs/recent`
7. `ai_agent_runs` persistence with safe summary columns only
8. prompt safety, response safety, and artifact safety

## Runtime Boundary

The smoke uses `host.docker.internal` to point `ai-agent-service` at a local OpenAI-compatible mock server started by the smoke script.

The mock server returns safe JSON sections only. It does not read the EDSP database and it does not receive secret material in the artifact.

The smoke script may inspect the database for the rows it created, but the artifact itself remains summary-only.

## Artifact Boundary

The smoke artifact is stored under:

`logs/ai-agent-runtime-smoke/<runId>/summary.json`

The artifact contains only aggregated verification data such as:

- run id
- compose project
- ports
- scenario statuses
- failure stage and failure type
- non-sensitive warnings

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

## Next Stage

If this stage succeeds, the next step is to keep runtime observability stable and move only if a new contract boundary is needed.
