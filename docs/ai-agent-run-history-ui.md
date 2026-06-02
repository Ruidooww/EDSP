# AI Agent Run History UI MVP

## Scope

This stage adds a customer-facing AI analysis history page backed by safe summaries from `ai_agent_runs`.

The page supports:

- filtering runs by status, source, provider, theme, and period
- reviewing provider, source, status, timestamps, duration, section count, and warning count
- opening a detail drawer with allowlisted metric keys and generated section titles
- confirming that sensitive fields were excluded from the detail response

## API

List endpoint:

```text
GET /api/core/ai-agents/runs?limit=20&status=passed&providerKey=fallback-template&period=last_7_days&theme=security_overview
```

Detail endpoint:

```text
GET /api/core/ai-agents/runs/{id}
```

The list endpoint clamps `limit` to a maximum of `100`.

## Safety Boundary

History responses use an allowlist. They may include:

- run identifiers and operational metadata
- provider and source identifiers
- status, safe error code, timestamps, and duration
- allowlisted aggregate metric keys
- section count and safe section titles
- safe warning codes

History responses must not include:

- raw prompt text
- raw model response bodies
- section body content
- endpoint URLs or API keys
- `payload_json`, `normalized_json`, `extra_json`, or `config_json`
- source configuration or secrets

The existing AI execution flow stores section titles with the output summary so that history can display them without storing or exposing generated section bodies.

## Follow-up

The next stage is `AI Agent Security Review / Prompt Injection Guard MVP`.
