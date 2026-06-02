# AI Agent RAG Readiness MVP

## Scope

This stage assesses safe Retrieval-Augmented Generation (RAG) for EDSP. It does not implement RAG ingestion, retrieval, embeddings, vector storage, file upload, or runtime APIs.

The current AI agent remains read-only. Existing prompt guards, response guards, redaction, Java final-response validation, and safe run-history persistence remain the required baseline for any later RAG implementation.

## Current Safety Baseline

The existing AI path already enforces:

- allowlisted aggregate context keys only;
- prompt injection detection before a model request;
- unsafe response fallback before generated content is returned;
- redaction for credentials, endpoints, HTTP URLs, JDBC URLs, payload JSON, and config JSON;
- Java final-response validation before persistence;
- safe run-history responses containing operational metadata, allowlisted metrics, section titles, warning codes, and error codes only;
- no raw prompt text, raw model response body, section body content, source config, secrets, or raw payload persistence in `ai_agent_runs`.

RAG must not weaken these boundaries.

## Candidate Knowledge Sources

### Allowed Candidate Sources

Only curated, reviewed, non-secret documentation should be considered:

1. Public product documentation written by the project team.
2. Sanitized SOPs.
3. Sanitized rule explanations.
4. Sanitized alert handling playbooks.
5. Sanitized architecture documents.
6. Non-secret API usage documents.

The first implementation candidate should use a dedicated reviewed path:

```text
docs/knowledge-base/**
```

Files outside that path must not be indexed by default.

### Forbidden Sources

The following content must never be indexed:

1. `.env` files.
2. Secrets, passwords, tokens, API keys, or credentials.
3. Raw source configuration.
4. Database dumps.
5. Raw event payloads.
6. User private files.
7. Complete logs with tokens or secret-bearing stack traces.
8. Notification endpoints.
9. Webhook URLs.
10. Authorization headers.
11. `payload_json`, `normalized_json`, `extra_json`, or `config_json`.
12. Raw prompts or raw model responses.

## Document Classification

Every future knowledge document must have an explicit classification before indexing:

| Classification | Indexing rule | Examples |
| --- | --- | --- |
| `public` | Allowed after review | Public product docs, non-secret API usage docs |
| `internal-sanitized` | Allowed after redaction and review | Sanitized SOPs, rule explanations, playbooks, architecture docs |
| `restricted` | Forbidden | Raw config, payloads, logs, credentials, private files |

Documents without a recognized classification must be rejected.

## RAG Security Contract

A later RAG runtime must implement all of the following controls:

1. **Ingestion allowlist:** index only reviewed files under an approved path such as `docs/knowledge-base/**`.
2. **Classification gate:** accept only `public` or `internal-sanitized` documents.
3. **Exclusion patterns:** reject forbidden filenames, paths, extensions, and sensitive text patterns before indexing.
4. **Redaction before indexing:** run redaction before chunking and reject documents when sensitive content remains.
5. **Provenance metadata:** store document path, classification, content hash, reviewer, indexed-at timestamp, and chunk identifier.
6. **Retrieval audit summary:** record safe operational metadata only: query category, retrieved document identifiers, chunk identifiers, classifications, chunk count, and elapsed time.
7. **Prompt injection handling:** treat retrieved text as untrusted content. Retrieved instructions must never override the system safety contract.
8. **Response guard reuse:** apply the existing unsafe-response guard and Java persistence guard after model generation.
9. **Maximum retrieved chunk count:** default to at most `5` chunks per request.
10. **Maximum retrieved token size:** default to at most `2,000` retrieved tokens per request.
11. **No automatic actions:** retrieved text must never trigger lifecycle changes, notifications, rule mutation, SQL execution, shell execution, or file access.
12. **No raw payload retrieval:** raw events, payload bodies, and sensitive configuration must remain unavailable to retrieval.

The future runtime must fail closed when classification, provenance, redaction, or retrieval limits cannot be verified.

## Exclusion Patterns

At minimum, a later ingestion guard must reject:

```text
.env
*.pem
*.key
*.p12
*.pfx
*secret*
*credential*
*token*
*password*
database dumps
raw event payload exports
complete logs
notification endpoints
webhook URLs
authorization headers
payload_json
normalized_json
extra_json
config_json
```

Pattern rejection is not sufficient by itself. Documents still require classification, redaction, and human review.

## Retrieval Audit Boundary

Safe retrieval audit records may contain:

```text
requestId
agentKey
providerKey
queryCategory
documentId
chunkId
classification
retrievedChunkCount
elapsedMs
safe error code
```

Audit records must not contain:

```text
raw query text
raw retrieved chunk text
raw prompt text
raw model response
secret values
endpoint URLs
authorization headers
payload JSON
config JSON
```

## Storage Options

| Option | Benefits | Risks and limitations | Recommendation |
| --- | --- | --- | --- |
| No RAG yet | Lowest security risk; no new runtime or storage | No retrieval-assisted explanations | Current stage: use this option |
| Local lightweight vector store | Simple local evaluation | Persistence, backup, and deployment consistency need design; easy to bypass governance if used ad hoc | Consider only for isolated experiments after explicit approval |
| PostgreSQL with `pgvector` | Reuses existing operational platform patterns; easier audit and access control | Requires extension, migration, lifecycle, backup, and query-boundary design | Preferred candidate for a later curated-docs MVP |
| External managed vector DB | Managed scale and search features | Adds data residency, credential, network, vendor, and audit concerns | Do not use as the first EDSP RAG implementation |

## Recommendation

Do not implement RAG runtime in this stage.

For a later MVP:

```text
Start with curated docs under docs/knowledge-base/**
Use reviewed public or internal-sanitized content only
Prefer PostgreSQL pgvector after explicit migration approval
Keep retrieval read-only and fully auditable
Reuse existing prompt, response, redaction, and persistence guards
```

## Future Implementation Candidate

Recommended next RAG implementation stage:

```text
AI Agent Curated Knowledge RAG MVP
```

That stage should remain separate from this readiness assessment and should define:

- curated document manifests;
- classification and review workflow;
- redaction and rejection tests;
- bounded chunking and retrieval;
- provenance metadata;
- retrieval audit summaries;
- prompt injection regression tests for retrieved content;
- migration compatibility if `pgvector` is approved.

## Explicitly Not Implemented

- Vector database.
- Embedding provider.
- `langchain`, `llamaindex`, `chromadb`, or `faiss`.
- RAG runtime API.
- File ingestion.
- File upload.
- Knowledge-base indexing job.
- Retrieval execution.
- Docker Compose vector service.
- Workflow changes.
- Database migration.

## Follow-up

The next recommended stage is `User Behavior Baseline Readiness MVP`.
