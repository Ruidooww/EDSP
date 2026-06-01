# EDSP AI Agent Platform Foundation With Docker Compose MVP

## Scope

This stage adds a read-only AI agent platform foundation. It does not modify alert lifecycle state, trigger notification delivery, run arbitrary SQL, read files, add RAG, or expose secrets.

## Architecture

The frontend calls `edsp-core` only. `edsp-core` aggregates allowlisted count metrics and calls the optional Python `ai-agent-service`. The Python service routes requests to local OpenAI-compatible, optional Ollama placeholder, cloud OpenAI-compatible, or safe fallback providers.

The Python service does not access the EDSP database. It only receives Java-generated count summaries. It does not receive raw rows, payload JSON, normalized JSON, extra JSON, source configuration, endpoint URLs, tokens, or credentials.

## Docker Compose

The Python service uses the optional `ai` profile and does not become a default startup dependency:

```powershell
docker compose --profile ai -p edsp up -d --build ai-agent-service
Invoke-RestMethod http://127.0.0.1:18090/health
docker compose -p edsp stop ai-agent-service
```

## Providers

- `local-openai-compatible`
- `local-ollama-compatible`, placeholder only
- `cloud-openai-compatible`
- `fallback-template`

Provider credentials are environment-only configuration. Discovery responses contain configuration booleans, never URL or credential values.

## Safe Boundary

`security-insight-agent` consumes only allowlisted count metrics. Python prompt guard, Python response guard, and Java fallback protect the output path. `ai_agent_runs` stores safe summaries only; it does not store raw prompts or raw model responses.

## Next Stage

Recommended next stage: `AI Agent Runtime Smoke Verification MVP`.

