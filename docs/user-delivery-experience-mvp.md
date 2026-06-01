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
