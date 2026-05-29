# Transform Rule Structured Payload / valueMap Readiness

## Summary

This document records the Transform Rule Structured Payload / valueMap
Readiness MVP.

This stage is docs-only / assessment-first. It does not implement `valueMap`,
does not modify DTOs or APIs, and does not modify backend, frontend,
migration, workflow, or script files. Its purpose is to define the future
contract, schema, execution boundary, and rollout path for structured
transform rule payloads before any implementation stage changes Java code.

## Current State

- Simple string `transformRule` execution is implemented in `edsp-transform`.
- Supported string rules are `trim`, `lower`, `upper`, and `defaultIfBlank`.
- Legacy unary forms are supported for `trim(sourceField)`,
  `lower(sourceField)`, and `upper(sourceField)`.
- `fieldMappings` remains the only authoritative mapping source.
- `fieldMappingDetails` must exactly match `{sourceField, standardField}` to
  execute a rule.
- Rules apply only to the mapped value and do not mutate the raw source row.
- `dedupFields` still uses the existing raw source row semantics.
- Precheck remains dry-run / schema metadata validation and does not execute
  rules.
- Runtime smoke verifies basic rules in remote success and fallback unavailable
  paths.
- `valueMap` is not implemented.
- The current DTO shape has only string `transformRule`; there is no
  structured payload field.

## Why valueMap Needs Structured Payload

`valueMap` should not be added as another ad hoc string syntax such as
`valueMap:a=b,c=d`.

Reasons:

- `valueMap` needs a key-value map, not a single scalar argument.
- A string syntax is brittle for multiple keys and values.
- Escaping delimiters inside keys and values would create parser complexity.
- String encoding is harder to audit and validate consistently.
- A string grammar would drift toward a DSL or expression language.
- Parser complexity would grow in `TransformRuleApplier`.
- A structured payload is better for validation, compatibility, and local /
  remote / fallback runtime consistency.

The recommended direction is not to make `valueMap` a complex string DSL.

## Candidate Field Names

| Field name | Readability | Compatibility | Ambiguity | Coexistence with `transformRule` | DTO extension cost | Assessment |
| --- | --- | --- | --- | --- | --- | --- |
| `transformRulePayload` | Clear. Indicates structured data for the rule. | Additive and optional. | Low. Does not imply arbitrary code. | Strong. `transformRule` stays the rule name. | Low. | Recommended. |
| `transformRuleJson` | Understandable, but format-oriented. | Additive. | Medium. Encourages thinking of JSON as a string blob. | Acceptable. | Low. | Not preferred. |
| `structuredTransformRule` | Clear but verbose. | Additive. | Medium. Could be mistaken for replacing `transformRule`. | Weaker. | Low. | Not preferred for MVP. |
| `transformRuleConfig` | Familiar configuration wording. | Additive. | Medium. Less explicit than payload. | Good. | Low. | Acceptable fallback. |

Recommended candidate field name: `transformRulePayload`.

This name is readable, additive, compatible with the existing string
`transformRule`, and describes structured payload rather than an arbitrary JSON
string. This stage does not add the field to any DTO.

## Recommended Future Payload Shape

Future mapping-detail attached payload shape:

```json
{
  "sourceField": "risk_level",
  "standardField": "severity",
  "transformRule": "valueMap",
  "transformRulePayload": {
    "type": "valueMap",
    "values": {
      "critical": "high",
      "warn": "medium"
    },
    "onMissing": "keepOriginal",
    "defaultValue": "info"
  }
}
```

Rules:

- `transformRule` remains the string rule name.
- `transformRulePayload` carries structured configuration.
- `transformRulePayload.type` must match `transformRule`.
- Simple rules can continue to omit `transformRulePayload`.
- `valueMap` requires `transformRulePayload`.

This shape is a future proposal only. It is not implemented in this stage.

## valueMap Minimal Schema Proposal

Minimal safe schema:

- `type`: exactly `"valueMap"`.
- `values`: a string-to-string map.
- `onMissing`: `"keepOriginal"` or `"useDefault"`.
- `defaultValue`: optional string.

Recommended constraints:

- `values` must be an object / map.
- `values` keys must be strings.
- `values` values must be strings.
- Object, array, boolean, and number outputs are not allowed.
- `onMissing` only allows `keepOriginal` and `useDefault`.
- `defaultValue` only applies when `onMissing=useDefault`.
- Missing key default should be `keepOriginal` plus warning.
- Invalid payload default should be keep original plus warning.
- First implementation should use case-sensitive key matching.
- No automatic trim, lower, or normalization of keys.
- No regex.
- No wildcard.
- No nested map.
- No lookup table reference.
- No SQL, HTTP, or filesystem lookup.

## Size / Safety Guards For Future Implementation

Suggested future guardrails:

- Maximum `values` entries: 200.
- Maximum key length: 200 characters.
- Maximum value length: 500 characters.
- Maximum structured payload JSON size: 32 KB.
- Warning messages must not include raw value.
- Warning messages must not include full raw row.
- Warning messages must not include source config.
- Warning messages must not include secret-like content.

These guardrails are recommendations only. This stage does not implement them.

## Missing Key Semantics

Options:

| Option | Behavior | Assessment |
| --- | --- | --- |
| A: keepOriginal + warning | Preserve input value and record `transform_rule_value_map_miss`. | Recommended default. Safe and non-blocking. |
| B: useDefault | Use `defaultValue` when explicitly configured. | Recommended only with `onMissing=useDefault` and present `defaultValue`. |
| C: blank / null | Replace missing values with blank or null. | Not recommended for MVP; can silently lose data. |
| D: hard error | Add row-level error or fail the row. | Not recommended; too disruptive for mapping refinement. |

Recommendation:

- Default missing key behavior is `keepOriginal` plus warning
  `transform_rule_value_map_miss`.
- If `onMissing=useDefault` and `defaultValue` exists, use `defaultValue`.
- Missing keys must not hard fail.
- Missing keys must not cause row failed, sync failed, or ShadowRun failed.

## Invalid Payload Semantics

Invalid payload cases:

- `transformRule=valueMap` but `transformRulePayload` is missing.
- `transformRulePayload.type` is not `valueMap`.
- `values` is missing.
- `values` is not an object / map.
- Any `values` key or value is not a string.
- `onMissing` is illegal.
- `onMissing=useDefault` but `defaultValue` is missing.

Recommendation:

- Treat invalid payload as no rule / keep original.
- Record a warning.
- Do not write row-level errors.
- Do not cause row failed.
- Do not cause sync failed.
- Do not cause ShadowRun failed.
- Do not leak raw value in warnings.

## Future Warning Codes

Proposed new warning codes:

- `transform_rule_value_map_miss`
- `transform_rule_value_map_invalid_payload`
- `transform_rule_value_map_invalid_values`
- `transform_rule_structured_payload_unsupported`

Existing warning codes remain valid:

- `transform_rule_unsupported`
- `transform_rule_invalid`
- `transform_rule_mismatch`

Warnings must not include raw value, full raw row, source config, or
secret-like content. Warnings must not cause row failed, sync failed, or
ShadowRun failed.

## No Chaining Boundary

This readiness stage does not design rule chaining.

Out of scope:

- Ordered rule lists.
- Nested `transformRule`.
- `trim -> lower -> valueMap` composition.
- Multiple rule execution in one mapping detail.
- Pipeline syntax.

If chaining is needed later, it should be planned as a separate Transform Rule
Chaining Readiness MVP.

## Compatibility With Existing String Rules

- `trim`, `lower`, `upper`, and `defaultIfBlank` continue to use string
  `transformRule`.
- Legacy unary forms remain compatible.
- `valueMap` is the first structured payload candidate.
- Any structured payload field must be additive.
- Old requests without `transformRulePayload` must continue to work.
- New requests with `transformRulePayload` must not change simple rule behavior.

## Runtime Consistency

Future `valueMap` execution should happen only inside `edsp-transform`.

It should not be executed in:

- `edsp-core`
- ShadowRun service code
- Precheck service code
- frontend
- transform-service controller
- transform-service mapper

Sync once and ShadowRun should receive `valueMap` results naturally through
`TransformRuntimeClient`. Remote, fallback, and local runtime modes should use
the same contract shape.

## Rejected Capabilities

The structured payload direction must not introduce:

- scripts
- Groovy
- JS
- SpEL
- `ScriptEngine`
- `eval`
- arbitrary expression execution
- SQL / database lookup
- HTTP calls
- filesystem access
- external process execution

These capabilities are rejected because they are difficult to audit, difficult
to sandbox, and would expand runtime and data leakage risk beyond a deterministic
mapping rule.

## Precheck Boundary

- Precheck remains dry-run / schema metadata validation.
- Precheck does not execute `valueMap`.
- Precheck does not call runtime.
- This readiness stage does not change activation gate behavior.
- Future Precheck runtime validation must be a separate stage.

## Artifact / Report Safety

Future runtime verification must preserve the current artifact safety boundary:

- Runtime smoke artifact should contain only summary-level output.
- It must not include full raw row.
- It must not include `payload_json`.
- It must not include source config.
- It must not include `data_sources.config_json`.
- It must not include full environment output.
- It must not include secret-like content.
- `valueMap` warnings must not leak raw source values.

## Future Implementation Path

### Next 1: Transform Rule Structured Contract MVP

Only do:

- Additive DTO / contract extension.
- Add optional `transformRulePayload`.
- Keep string `transformRule` compatible.
- Pass structured payload through `TransformPlanSupport`.
- Pass structured payload through `MappingPlan`.
- Do not execute `valueMap`.

### Next 2: Transform Rule valueMap Processor MVP

Only do:

- Execute `valueMap` inside `edsp-transform`.
- Support one `valueMap` per authoritative mapping detail.
- Do not support chaining.
- Do not support regex, wildcard, script, SQL, HTTP, or filesystem access.
- Missing key and invalid payload produce warnings, not hard failures.

### Next 3: Transform Rule valueMap Runtime Verification MVP

Only do:

- Runtime smoke coverage for remote success.
- Runtime smoke coverage for fallback unavailable.
- Safe summary-only artifact.
- Do not set required check.

### Optional Later Stages

- Transform Rule UI / Mapping Rule Configuration MVP.
- Transform Rule Chaining Readiness MVP.

## Final Recommendation

- Do not directly implement `valueMap` now.
- Do not extend `valueMap` as a complex string DSL.
- Prefer an additive structured payload field named `transformRulePayload`.
- Use minimal schema: `type`, `values`, `onMissing`, and `defaultValue`.
- Default missing key behavior should be `keepOriginal` plus warning.
- Invalid payload should not hard fail.
- Do not support chaining.
- Do not support script, expression, SQL, HTTP, or filesystem access.
- Next stage should be Transform Rule Structured Contract MVP.
