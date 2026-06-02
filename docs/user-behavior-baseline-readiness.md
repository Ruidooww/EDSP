# User Behavior Baseline Readiness MVP

## Scope

This stage is a readiness assessment for deterministic user or entity behavior baselines.

It does not implement baseline computation, scheduled aggregation, AI scoring, alert decision writes, database migrations, raw timeline export, or PII enrichment.

The purpose is to define the minimum safe data contract and a feasible implementation path before any scoring stage starts.

## Current Data Baseline

The current event pipeline already provides a useful deterministic foundation:

- `standard_events` is the normalized event boundary.
- `standard_events.source_system`, `standard_events.occurred_at`, `standard_events.severity`, `standard_events.actor`, `standard_events.subject_type`, and `standard_events.subject_ref` are first-class columns.
- `alert_decisions.standard_event_id`, `alert_decisions.decision`, and `alert_decisions.severity` support deterministic matched-decision counts after joining back to `standard_events`.
- `alerts.standard_event_id`, `alerts.alert_decision_id`, `alerts.status`, and `alerts.severity` support deterministic alert counts after joining back to `standard_events`.
- `ingestion_plan_sync_runs.data_source_id`, `ingestion_plan_sync_runs.status`, and its count fields support source-level sync quality summaries.
- `SemanticProfilerService` recognizes candidate subject identifier field names in source schemas, including `user_id`, `account_id`, `employee_id`, and `device_id`.

Candidate recognition is not a stable baseline identity contract. It is metadata evidence for a later mapping decision only.

## Candidate Entity Key Assessment

| Candidate key | Current availability | Readiness decision |
| --- | --- | --- |
| `user_id` | Not a first-class `standard_events` column. Source schemas may expose it as a candidate subject field. | `needs follow-up`: define an approved mapping into a pseudonymous entity key before aggregation. |
| `account_id` | Not a first-class `standard_events` column. Source schemas may expose it as a candidate subject field. | `needs follow-up`: confirm source meaning and mapping precedence. |
| `employee_id` | Not a first-class `standard_events` column. Source schemas may expose it as a candidate subject field. | `needs follow-up`: confirm whether use is necessary and approved under privacy rules. |
| `device_id` | Not a first-class `standard_events` column. It may be recognized as an asset candidate rather than a user identity. | `needs follow-up`: keep device and user baselines distinct. |
| `source_system` | First-class non-null `standard_events.source_system` column. | `available`: safe as a grouping dimension, not as a user identity. |
| `department` | No first-class normalized event column. | `needs follow-up`: do not enrich or infer it in this stage. |
| `role` | No first-class normalized event column. | `needs follow-up`: do not enrich or infer it in this stage. |

The existing `actor`, `subject_type`, and `subject_ref` columns are useful normalized signals, but they do not yet prove a stable user identity. A later implementation must define which event types may use them, whether values are pseudonymous, and how conflicting source fields are resolved.

## Minimum Future Entity Contract

A future baseline implementation should start only after an explicit contract is approved for:

| Field | Requirement |
| --- | --- |
| `entity_key` | Stable pseudonymous identifier. Do not expose raw personal identifiers to the model or UI. |
| `entity_type` | Explicit type such as `user`, `account`, or `device`. Do not merge types implicitly. |
| `source_system` | Required provenance dimension from `standard_events.source_system`. |
| `occurred_at` | Required event-time boundary from `standard_events.occurred_at`, with a documented fallback policy if missing. |
| `severity` | Deterministic normalized severity from `standard_events.severity`. |
| `mapping_version` | Versioned mapping rule so aggregates can be audited and rebuilt. |

The contract must be derived from curated normalized columns or an approved transformation rule. It must not read raw payload content at aggregation time.

## Safe Aggregate Feature Assessment

| Candidate feature | Current feasibility | Required boundary |
| --- | --- | --- |
| Event count per entity per day | Feasible after entity contract approval. | Count normalized `standard_events` only. |
| High-risk event count per entity per day | Feasible after entity contract approval. | Use deterministic normalized `severity`; document which levels are high risk. |
| After-hours ratio | Feasible after entity contract approval. | Use `occurred_at`; define timezone and approved business-hour policy. |
| Source-system diversity | Feasible after entity contract approval. | Count distinct `source_system`; do not inspect raw payloads. |
| Alert count per entity | Feasible after entity contract approval. | Join `alerts.standard_event_id` to normalized events. |
| Matched-decision count per entity | Feasible after entity contract approval. | Join `alert_decisions.standard_event_id` and count only `decision = 'matched'`. |
| Failed-sync association count | Partially feasible at source level only. | `ingestion_plan_sync_runs` supports source-level failure summaries. Entity-level association is `needs follow-up` and must not be guessed. |
| Severity distribution | Feasible after entity contract approval. | Aggregate normalized `standard_events.severity`; expose counts or ratios only. |

All features remain deterministic aggregates. They are not model inputs until a later scoring plan explicitly approves the feature contract.

## Baseline Windows

Recommended deterministic windows:

- `7-day`: short-term activity comparison.
- `30-day`: primary rolling baseline.
- `90-day`: optional later window after storage, retention, and privacy review.

The first implementation should use daily aggregate buckets. It should not retain or export a raw per-user activity timeline.

## Privacy And Data Minimization

The baseline boundary must enforce:

- aggregate-only storage and model context;
- pseudonymous `entity_key` values where possible;
- no PII enrichment unless a concrete requirement and explicit approval exist;
- no raw event payload export to a model;
- no full personal-data records;
- safe UI output limited to counts, ratios, relative changes, and audited summary dimensions;
- retention rules for aggregate buckets before the optional `90-day` window is enabled.

## Forbidden Baseline Inputs

The baseline path must not use:

- raw file content;
- sensitive full file paths;
- raw payload bodies from `raw_events.payload_json`;
- unreviewed `standard_events.normalized_json` or `standard_events.extra_json` blobs;
- credentials, secrets, or tokens;
- private message content;
- raw notification endpoints;
- full personal-data records;
- inferred `department` or `role` values from unapproved sources.

JSON fields may contain useful evidence for future mapping design, but production aggregation must use curated normalized fields or an explicitly approved extraction contract.

## Implementation Options

| Option | Strengths | Risks | Readiness decision |
| --- | --- | --- | --- |
| SQL aggregate queries over normalized events | Simple, auditable, deterministic, easy to validate. | Repeated queries can become expensive without aggregate tables and retention rules. | Preferred starting point for contract validation. |
| Java scheduled aggregation in `edsp-core` | Fits current module ownership and can persist versioned daily buckets later. | Requires explicit schema design, migration approval, idempotency, and schedule controls. | Recommended first production implementation after contract approval. |
| Python offline analysis | Useful for exploratory analysis outside runtime. | Higher risk of data export and drift from production rules. | Optional only with sanitized aggregate extracts. |
| AI explanation after deterministic aggregation | Can explain audited deviations using bounded aggregate context. | Must not become the score source or receive raw event timelines. | Later stage only, after deterministic aggregates are approved. |

## Recommended Path

1. Approve a versioned pseudonymous entity mapping contract for selected source systems.
2. Validate deterministic SQL aggregates against `standard_events`, `alert_decisions`, and `alerts`.
3. Add Java-owned, idempotent daily aggregate persistence only in a separately approved stage with an explicit migration.
4. Keep scores deterministic and audited before adding any AI explanation.
5. Allow AI explanation only from bounded aggregate summaries, never raw payloads or raw timelines.

## Explicitly Not Implemented

This MVP intentionally does not add:

- a machine-learning model;
- baseline tables or migrations;
- a baseline computation job;
- a scheduler;
- a scoring endpoint;
- an alert decision write path;
- AI-generated risk scores;
- raw user timeline export;
- PII enrichment;
- frontend baseline pages;
- workflow, Docker Compose, or AI agent service changes.

## Next Stage

Recommended next stage: `AI Risk Scoring To Alert Decisions MVP`.

That stage must remain gated on an approved deterministic entity and aggregate feature contract. It must not bypass `standard_events` or write alerts directly.
