# Transform Rule Contract Readiness MVP

## Summary

This stage is a docs-only / assessment-first readiness pass for configurable
`transform_rule` contract design.

This stage does not implement `transform_rule` execution. It does not modify
DTOs, APIs, backend code, frontend code, migrations, workflow files, scripts, or
runtime behavior.

The goal is to define how rules should eventually move from `field_mappings` and
`plan_json` into the transform runtime contract without widening scope in the
current stage.

## Current State

- `field_mappings.transform_rule` already exists as a nullable `text` column.
- `SchemaController` persists `transform_rule` through `POST /mappings` using
  `FieldMappingRequest.transformRule`.
- `IngestionPlanService` preserves existing mapping rules in
  `plan_json.fieldMappingDetails[*].transformRule`.
- `IngestionPlanService` also preserves rule evidence in
  `plan_json.fieldEvidence[*].transformRule` and `transformRules`.
- `plan_json.fieldMappings` currently remains only a `sourceField ->
  standardField` map.
- `TransformPlanSupport` currently extracts `fieldMappings`, `dedupFields`,
  selected fields, and options, but drops or ignores `transformRule`.
- `TransformMappingPlanDto` currently carries only `fieldMappings` and
  `dedupFields`.
- `edsp-transform` `MappingPlan` currently carries only `fieldMappings` and
  `dedupFields`.
- `StandardEventTransformRuleProcessor` currently executes only the existing
  hard-coded standard event transform logic.
- Runtime clients currently do not receive or execute configurable rules.

## Current Data Flow

Current rule-related data flow:

```text
field_mappings.transform_rule
-> SchemaController mapping persistence
-> IngestionPlanService existing mapping evidence
-> plan_json.fieldMappingDetails[*].transformRule
-> plan_json.fieldEvidence.transformRule / transformRules
-> TransformPlanSupport currently drops/ignores rules
-> TransformMappingPlanDto only carries fieldMappings + dedupFields
-> TransformRuntimeClient sends no rule payload
-> edsp-transform MappingPlan receives no rule payload
-> StandardEventTransformRuleProcessor cannot execute configurable rules
```

Current storage and plan generation details:

- The database column is named `transform_rule`.
- The API field is `transformRule`.
- The value can be null or blank; there is no current format validation.
- The value is currently a plain string.
- `fieldMappingDetails` is the only current `plan_json` structure that preserves
  `sourceField`, `standardField`, and `transformRule` together.
- `fieldEvidence.transformRule` / `fieldEvidence.transformRules` is useful for
  explanation, but should not become the runtime contract source.

## Gap Analysis

- `plan_json` has rule evidence, but rule evidence is not a runtime contract.
- `TransformMappingPlanDto` has no rule field.
- `MappingPlan` has no rule field.
- The transform-service batch API has no rule wire shape.
- `TransformContractMapper` maps only `fieldMappings` and `dedupFields`.
- `TransformContractSupport` maps only `fieldMappings` and `dedupFields`.
- `StandardEventTransformRuleProcessor` has no configurable rule execution.
- `sync once` and `ShadowRun` already use `TransformRuntimeClient`, but cannot
  pass rules because the contract has no rule payload.
- `Precheck` remains dry-run / schema metadata validation and should not execute
  rules in this stage.

## Rule Binding Options

| Option | Pros | Cons | Risks | Recommendation |
| --- | --- | --- | --- | --- |
| Option A: source-field binding, rules keyed by `sourceField` | Simple lookup from source row. Fits current `fieldMappings` key shape. | Ambiguous when one source field maps to multiple standard fields. Rule intent can drift from target field semantics. | A rule intended for `severity` could accidentally apply when the same source feeds another target. | Not recommended as the primary contract. |
| Option B: standard-field binding, rules keyed by `standardField` | Aligns with output field intent. Useful for standard field validation. | Ambiguous when multiple source fields can feed one standard field. Loses exact source mapping context. | Rule ordering and duplicate target mappings become unclear. | Not recommended as the primary contract. |
| Option C: mapping-detail based transport, rules carried with `{ sourceField, standardField, transformRule }` | Keeps rule attached to the exact mapping edge. Matches existing `plan_json.fieldMappingDetails`. Supports future multiple mapping details without loose top-level joins. | Requires a new contract shape instead of the current flat map. Slightly more verbose. | Requires compatibility discipline when extending DTOs and runtime mapping. | Recommended. |

Current recommendation: use Option C, mapping-detail based transport.

Reasons:

- `plan_json.fieldMappingDetails` already preserves `sourceField`,
  `standardField`, and `transformRule`.
- It avoids loose top-level `transformRules` drifting out of sync with
  `fieldMappings`.
- It can naturally support one `sourceField` mapping to multiple `standardField`
  values or multiple future mapping details.
- It is the best fit for a compatibility extension to `TransformMappingPlanDto`.

## Minimal Safe Rule Set Proposal

These rules are future proposals only. This stage does not implement them.

| Rule | Input | Output | Deterministic | Side-effect-free | Error behavior proposal |
| --- | --- | --- | --- | --- | --- |
| `trim` | scalar value | string with surrounding whitespace removed | yes | yes | non-scalar input should produce a rule warning or leave value unchanged by explicit policy. |
| `lower` | scalar value | lowercase string | yes | yes | non-scalar input should produce a rule warning or leave value unchanged by explicit policy. |
| `upper` | scalar value | uppercase string | yes | yes | non-scalar input should produce a rule warning or leave value unchanged by explicit policy. |
| `defaultIfBlank` | scalar value plus configured default | original value or default | yes | yes | missing default should produce a rule error. |
| `valueMap` | scalar value plus configured lookup map | mapped value | yes | yes | missing key should follow an explicit policy: warning and keep original, warning and null, or error. |

Future implementation must define rule order, invalid payload handling, and
whether rule warnings block draft construction.

## Explicitly Rejected Rule Capabilities

The MVP rule system should reject these capabilities:

- scripts
- Groovy
- JS
- SpEL
- SQL/database lookup
- HTTP calls
- filesystem access
- multi-row stateful rules
- arbitrary expressions
- reflection
- external process execution

Reasons:

- High security risk.
- Hard to audit.
- Hard to sandbox.
- Non-deterministic or environment-dependent behavior.
- Larger runtime surface area.
- Higher risk of raw row, source config, or secret-like data leakage.

## Future Contract Shape Options

| Option | Compatibility | Remote runtime impact | Local runtime impact | ShadowRun impact | Precheck impact | Frontend impact | Recommendation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Option 1: add optional `fieldMappingDetails` to `TransformMappingPlanDto` | Good if optional and old `fieldMappings` remains. | Enables batch API to carry mapping-detail rule payload. | `TransformContractSupport` can map details into `MappingPlan`. | ShadowRun naturally receives the same runtime result through `TransformRuntimeClient`. | No impact unless a later Precheck runtime stage opts in. | Can align with existing normalized plan shape. | Recommended direction. |
| Option 2: add optional top-level `transformRules` map | Easier DTO addition. | Remote can receive rules, but must join them back to mappings. | Local must duplicate join logic. | Higher chance of source/target drift. | No immediate impact. | Harder to explain when mappings duplicate. | Not preferred. |
| Option 3: add a new `TransformFieldMappingDto` list | Cleanest typed model. | Clear wire shape for remote. | Clear internal conversion to `MappingPlan`. | Clean runtime parity. | No immediate impact. | Future UI can target same model. | Good long-term option, possibly the concrete form of Option 1. |

Current recommendation: extend `TransformMappingPlanDto` in a later stage with
an optional mapping-detail style list. A concrete DTO could be a
`TransformFieldMappingDto` list containing `sourceField`, `standardField`, and
`transformRule`.

This stage does not modify DTOs or APIs.

## Runtime Behavior Proposal

Future rule execution should happen only inside `edsp-transform`, specifically
behind the internal `StandardEventTransformRuleProcessor` boundary.

Do not copy configurable rule execution into:

- `edsp-core`
- `IngestionPlanSyncOnceService`
- `IngestionPlanShadowRunService`
- `IngestionPlanPrecheckService`
- frontend code
- transform-service controller code

Expected future flow:

```text
plan_json mapping details
-> TransformPlanSupport builds extended TransformMappingPlanDto
-> TransformRuntimeClient sends BatchTransformRequest
-> local / remote / fallback runtime receives the same contract shape
-> edsp-transform MappingPlan receives mapping details
-> StandardEventTransformRuleProcessor executes approved deterministic rules
```

With this shape, `sync once` and `ShadowRun` get consistent results through the
same `TransformRuntimeClient` path. `remote`, `fallback`, and `local` modes also
stay aligned because the same contract carries the rule payload.

## Error / Warning Semantics

Future rule execution should define explicit sanitized errors and warnings for:

- unsupported rule type
- invalid rule payload
- `valueMap` missing key
- `defaultIfBlank` missing default
- rule output invalid for the target standard field

Future recommendation:

- Rule errors should enter `TransformResult.errors` when the output is unsafe or
  invalid.
- Rule warnings should enter `TransformResult.warnings` when execution can
  safely continue.
- Errors and warnings must not include full raw rows, JDBC credentials, source
  config, data source config JSON, tokens, cookies, authorization headers, or
  secret-like values.
- Runtime exceptions should be sanitized before entering sync or shadow reports.
- `remote` / `fallback` semantics must remain clear: remote transport failure is
  a runtime failure; deterministic rule validation failure is a transform result
  problem.

## Precheck Boundary

This stage keeps `Precheck` unchanged.

- `Precheck` still performs dry-run / schema metadata validation.
- `Precheck` does not call `TransformRuntimeClient`.
- `Precheck` does not execute rules.
- Future Precheck rule validation is a separate stage.
- If future Precheck should validate runtime rule behavior, use the already
  assessed two-stage Precheck direction: schema guard first, optional runtime
  validation second.

## Future Implementation Path

Recommended sequence:

1. `Transform Rule Contract MVP`
   - Add compatibility DTO / contract extension.
   - Let `TransformPlanSupport` pass rule payload.
   - Let `MappingPlan` receive rule payload.
   - Keep rule execution as no-op passthrough or explicitly disabled.
   - Do not change runtime semantics beyond carrying the payload.

2. `Configurable Transform Rule Processor MVP`
   - Execute only approved deterministic rules in `edsp-transform`.
   - Initial rule set: `trim`, `lower`, `upper`, `defaultIfBlank`, `valueMap`.
   - Keep implementation inside `StandardEventTransformRuleProcessor`.
   - Do not add script execution or external access.

3. `Transform Rule Runtime Verification MVP`
   - Verify local / remote / fallback parity.
   - Verify `sync once` and `ShadowRun` use the same rule behavior.
   - Extend runtime smoke or API tests only after contract and processor behavior
     are stable.

4. `Frontend Mapping Rule UI MVP` (optional)
   - Only after backend contract and runtime behavior are stable.
   - Keep UI wording aligned with the approved safe rule set.

## Final Recommendation

- Do not implement `transform_rule` execution now.
- Use mapping-detail based transport as the future contract direction.
- Implement `Transform Rule Contract MVP` before rule execution.
- Implement `Configurable Transform Rule Processor MVP` only after contract
  shape is approved.
- Keep `Precheck` as dry-run / schema metadata validation in this stage.
- Keep `Transform Runtime Smoke` as a non-required PR check while collecting
  more PR samples.
