# AI Agent Provider Configuration Readiness

This stage adds a readiness UI for AI provider access. It is not an API key persistence platform.

## Scope

- System settings show model provider readiness for local models, enterprise cloud models, and the built-in safe template.
- Java core exposes sanitized provider configuration status through `GET /api/core/ai-agent-provider-configs`.
- Java core exposes sanitized provider connection testing through `POST /api/core/ai-agent-provider-configs/{providerKey}/test`.
- The Python AI service exposes `POST /agent/providers/{provider_key}/test` for provider-level connection checks.
- The AI operation advice page uses provider readiness to disable unavailable enterprise cloud models.

## Secret Boundary

API keys are write-only, boolean-only, and masked-only in this stage.

Allowed frontend display:

- `API Key: 已配置`
- `API Key: 未配置`
- `API Key: 不需要`

Forbidden in API responses, AI run history, audit logs, smoke artifacts, and UI state:

- raw API key values
- `Authorization` header values
- bearer token values
- raw request bodies containing secrets
- raw model response bodies
- full `.env` values

The UI may show environment variable names so administrators know what to configure. Example values must stay masked:

```text
EDSP_AI_CLOUD_OPENAI_ENABLED=true
EDSP_AI_CLOUD_OPENAI_BASE_URL=https://example.com/v1/chat/completions
EDSP_AI_CLOUD_OPENAI_API_KEY=********
EDSP_AI_CLOUD_OPENAI_MODEL=your-model-name
```

## Configuration Model

Third-party cloud model configuration still comes from deployment environment variables:

- `EDSP_AI_CLOUD_OPENAI_ENABLED`
- `EDSP_AI_CLOUD_OPENAI_BASE_URL`
- `EDSP_AI_CLOUD_OPENAI_API_KEY`
- `EDSP_AI_CLOUD_OPENAI_MODEL`

The settings page does not save keys to the database and does not send a key from the browser to the backend.

## Provider Test Behavior

Provider tests return only customer-readable results:

- `passed`: the provider connection test completed.
- `failed`: the provider is disabled, incomplete, unreachable, timed out, or failed authentication.

Failure messages are mapped to safe explanations such as:

- 模型接口未配置，请联系管理员检查部署环境变量。
- 认证失败，请检查 API Key。
- 模型或接口路径不可用，请检查接口地址和模型名称。
- 连接超时，请稍后重试或检查网络。
- 接口不可达，请检查接口地址。

Provider tests do not return raw URLs with query strings, raw headers, raw request bodies, raw model responses, stack traces, or raw secrets.

## AI Run History

AI run history continues to store only safe summaries:

- provider key used for routing and auditability
- source and status
- section count
- warning summary
- aggregate context counts

It does not store raw prompts, raw responses, API keys, authorization headers, or customer row payloads.

## Follow-Up

`AI Agent Provider Secure Persistence MVP` can be a later independent stage if the product needs page-based API key saving, encryption, rotation, and dynamic runtime configuration.
