# EDSP User Delivery Experience MVP

## Scope

This stage converts frontend customer-facing pages from engineering/debug wording to delivery-ready wording. It does not change backend production code, migrations, `ai-agent-service`, compose files, workflows, `HANDOFF.md`, or `AGENTS.md`.

## Task 0 Engineering Field Audit

Task 0 was executed before page refactoring. Search scope was `frontend/src`.

Initial engineering keyword search:

```text
providerKey|fallback-template|local-openai-compatible|cloud-openai-compatible|local-ollama-compatible|Decision ID|standardEventId|ruleId|raw JSON|riskScore|minSeverity|last_7_days|high_risk_alerts|security_overview|sync_health|notification_readiness|warning|passed|failed|source|llm|payload_json|normalized_json|extra_json|config_json|operatorName|epoch|timestamp
```

Initial top hit groups:

```text
96 types.ts
73 pages/schema/SchemaPage.tsx
70 pages/DataSourcesPage.tsx
47 pages/CollectionTasksPage.tsx
40 pages/AlertSyncPage.tsx
40 pages/RulesPage.tsx
38 pages/DashboardPage.tsx
29 pages/AlertsPage.tsx
28 pages/schema/utils/normalizeIngestionPlan.ts
27 pages/schema/components/IngestionPlanRuleConfigDrawer.tsx
24 pages/NotificationsPage.tsx
19 pages/schema/components/IngestionPlanPanel.tsx
15 App.tsx
12 pages/AiAgentPage.tsx
```

Initial Chinese/debug keyword search:

```text
手动执行|手动生成|编号|技术|调试|原始|表达式|标准事件|规则决策|运行记录|执行结果|失败原因|返回值|接口|同步运行|Shadow|Precheck|activation|raw|debug
```

Initial top hit groups:

```text
133 pages/schema/SchemaPage.tsx
49 pages/schema/components/IngestionPlanPanel.tsx
40 pages/schema/components/IngestionPlanActions.tsx
29 types.ts
25 pages/CollectionTasksPage.tsx
24 pages/schema/utils/ingestionPlanActivation.ts
21 pages/schema/components/IngestionPlanSection.tsx
18 pages/schema/components/IngestionPlanShadowRunReportDrawer.tsx
13 pages/schema/components/IngestionPlanPrecheckDrawer.tsx
10 pages/AlertsPage.tsx
```

Post-refactor engineering keyword search still finds code-level backend contract names and collapsed advanced details:

```text
96 types.ts
72 pages/schema/SchemaPage.tsx
71 pages/DataSourcesPage.tsx
47 pages/CollectionTasksPage.tsx
41 pages/AlertSyncPage.tsx
38 pages/DashboardPage.tsx
38 utils/businessDisplay.ts
38 pages/RulesPage.tsx
27 pages/AlertsPage.tsx
28 pages/schema/utils/normalizeIngestionPlan.ts
```

Post-refactor Chinese/debug keyword search still finds internal schema identifiers and advanced implementation components:

```text
118 pages/schema/SchemaPage.tsx
49 pages/schema/components/IngestionPlanPanel.tsx
39 pages/schema/components/IngestionPlanActions.tsx
29 types.ts
24 pages/schema/utils/ingestionPlanActivation.ts
23 pages/CollectionTasksPage.tsx
21 pages/schema/components/IngestionPlanSection.tsx
17 pages/schema/components/IngestionPlanShadowRunReportDrawer.tsx
14 pages/AlertsPage.tsx
12 pages/schema/components/IngestionPlanPrecheckDrawer.tsx
```

Treatment:

- Default customer-facing labels were rewritten to business terms, for example `Raw 事件` to `接收事件明细`, `Shadow Run` to `试运行`, `providerKey` to `分析模型`, and raw status values to Chinese status tags.
- Debug/admin-only values such as `decisionId`, `standardEventId`, `ruleId`, `payloadJson`, `providerKey`, and raw status values were moved into default-collapsed advanced details.
- Directly technical tools such as manual rule evaluation, notification secret migration checks, and generated alert backfill were placed behind default-collapsed advanced sections.
- Code identifiers and DTO fields were retained in `types.ts`, API payload construction, utility functions, and normalized schema helpers because they are frontend/backend contract names. Renaming them would break API compatibility and is outside this frontend delivery stage.
- Source field names from customer systems were retained in metadata and mapping views because they are customer data labels required for implementation validation.

## Post-P1/P2 Engineering Field Re-Audit

After fixing the notification backfill confirmation contract, CodeGraph and keyword fallback scans were re-run.

### CodeGraph scan

- scanned by CodeGraph: yes
- scan scope: `frontend/src/**`
- semantic focus: `AdvancedDetailsCollapse`, `AiAgentPage`, `AlertsPage`, `RulesPage`, `NotificationsPage`, `CollectionTasksPage`, and `businessDisplay` mapping helpers
- default-visible engineering fields found:
  - `api.ts`: the generic fallback error `Request failed: ${response.status}` was fixed to `服务暂不可用，请稍后重试` (A. Fixed in default user view).
  - `NotificationsPage`: the user-facing hint `需要输入 endpoint 才能启用` was fixed to `需要输入通道地址才能启用` (A. Fixed in default user view).
  - `CollectionTasksPage`: the user-facing hint `技术来源已记录` was fixed to `来源已记录` (A. Fixed in default user view).
- moved to advanced/details and default-collapsed:
  - `AiAgentPage`: `providerKey`, `source`, `status`, `warnings`, and run metadata are kept in `AdvancedDetailsCollapse` or expandable table rows (B).
  - `AlertsPage`: `decisionId`, `standardEventId`, `ruleId`, `detailJson`, and manual alert generation are kept in advanced details or a default-collapsed advanced tool (B).
  - `RulesPage`: `standardEventId`, `ruleId`, `decision`, `riskScore`, `detail JSON`, `riskScoreThreshold`, and `minSeverity` are kept in advanced details or a default-collapsed advanced tool (B).
  - `NotificationsPage`: `alertId`, `retryOfDeliveryId`, `responseBody`, `payloadJson`, raw delivery payloads, and the notification secret migration tool are kept in advanced details/admin collapse sections (B).
- code/API-only retained:
  - `providerKey`, `fallback-template`, `local-openai-compatible`, `cloud-openai-compatible`, `local-ollama-compatible`, raw status keys, query parameters, DTO fields, API paths, and `EXECUTE_NOTIFICATION_SECRET_BACKFILL` remain as frontend/backend contract names (C).
- customer mapping fields retained:
  - source system names, external event IDs, standard event business labels, and external API address labels remain visible where they are customer-facing mapping or integration data (D).
- remaining follow-up: none known for default-visible engineering fields after this scan.

### Keyword fallback scan

- scanned by `Select-String`: yes, with `Get-ChildItem -Recurse` over all `frontend/src` `.tsx`, `.ts`, and `.css` files so top-level files such as `frontend/src/api.ts` are included
- engineering keyword hits reviewed: yes
- Chinese/debug keyword hits reviewed: yes
- result summary:
  - no remaining default-visible `Request failed`, `Internal Server Error`, `invalid_confirmation`, `unknown error`, `Decision ID`, `raw JSON`, `Shadow Run`, or `Shadow Precheck` hits were found.
  - raw keys such as `decisionId`, `standardEventId`, `ruleId`, `payloadJson`, `responseBody`, `providerKey`, `riskScore`, and `minSeverity` are retained only in code/API contracts or default-collapsed advanced details.
  - raw status values such as `warning`, `passed`, `failed`, `completed_with_failures`, `migration_eligible`, `matched`, and `not_matched` are retained as mapping keys in `businessDisplay` or API comparison code; default visible UI uses Chinese labels.
  - Chinese/debug hits such as `高级`, `技术详情`, `管理员工具`, `规则决策编号（高级）`, `标准化事件编号（高级）`, `通道编号（高级）`, `失败原因`, and `执行结果` were reviewed. Technical identifiers are either marked as advanced/default-collapsed, admin-only, or are business-facing operational labels.

### Fix result

- notification backfill display phrase remains Chinese: `确认迁移`
- backend API confirmation remains: `EXECUTE_NOTIFICATION_SECRET_BACKFILL`
- local mismatch error is localized: `确认文字不匹配`
- execution failure toast is localized: `密钥迁移执行失败：...`
- backend contract unchanged
- no backend / migration / workflow / AI runtime / compose changes
