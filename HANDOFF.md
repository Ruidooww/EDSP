# 数据安全预警分析平台 Handoff

> 更新时间：2026-05-23
> 当前稳定分支：`master`
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Rule Evaluation MVP`
- 最新 feature merge commit：`368f372 merge: rule evaluation mvp`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for rule evaluation mvp`
- 本轮分支：`codex/rule-evaluation-mvp`
- 本轮结果：已完成手动规则评估 MVP。系统可以对指定 `standard_event` 执行 enabled rules，写入或更新 `alert_decisions`，但不会创建 `alerts`、不会触发通知、不会接入 AI。

## 已完成能力

- 已完成 Database Intelligence Ingestion Plan MVP、Shadow Validator MVP、Ingestion Plan Quality Hardening、Activation Gate MVP、Ingestion Plan Sync Once MVP、Ingestion Plan Sync Once Hardening、Scheduled Sync MVP，并合并到 `master`。
- 已完成 Rule Evaluation MVP。
- 新增 `RuleDecisionRunner`，负责编排一次规则评估：加载指定 `standard_events`、加载 rules、调用 evaluation core、写入 decisions。
- 新增 `RuleEvaluationService`，作为纯规则评估核心，只处理 `StandardEventContext` + structured rule config 并返回 evaluation result。
- 新增 `RuleDecisionRepository`，封装 `alert_decisions` upsert 和列表查询。
- 新增 `RuleService`，提供 rules list/create/enable 能力。
- 新增 `V12__rule_decision_idempotency.sql`，为 `alert_decisions(standard_event_id, rule_id)` 增加幂等约束，并补充查询索引和 decision 状态约束。
- `rules.expression` 明确为 structured JSON config，不是 DSL、不是 template engine。
- MVP 支持 `timeWindow`、`minSeverity`、`threshold.metric`、`threshold.operator`、`threshold.value`。
- operator 仅支持 `>=`、`>`、`<=`、`<`、`==`。
- decision 状态固定为 `matched`、`not_matched`、`skipped`、`error`。
- 同一 `standard_event_id + rule_id` 重跑使用 upsert，不重复插入。
- 新增 API：
  - `GET /api/core/rules`
  - `POST /api/core/rules`
  - `PUT /api/core/rules/{id}/enabled`
  - `POST /api/core/rule-evaluations/run`
  - `GET /api/core/rule-evaluations?standardEventId=123&limit=50`
- 前端 `RulesPage` 已切到 core rules / rule evaluations API。
- 前端文案使用“执行规则评估”，并明确本阶段只写 `alert_decisions`，不会创建 `alerts`，不会触发通知。

## 明确未做 / 禁止误解

- 未做 Alerts MVP。
- 未创建 `alerts`。
- 未触发通知。
- 未接入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。
- 未做批量扫描。
- 未做定时规则评估。
- 未做实时流评估。
- 未在 sync-once 或 scheduled sync 完成后自动调用 `RuleDecisionRunner`。
- 未引入复杂 DSL。
- 未执行动态脚本、SpEL、Jinja、自定义 SQL、自定义函数或运行时表达式。
- 未把 collection、rules、alerts、notifications、AI 混在同一阶段。
- 未修改 `AGENTS.md`。

## 当前关键边界

- 规则评估动作是 `rule evaluation`，产物是 `alert_decisions`。
- 本阶段允许写 `alert_decisions`，但禁止写 `alerts`。
- `rules.severity` 表示规则命中后的 decision severity，不作为 event severity 阈值。
- event 最低等级阈值只看 structured config 中的 `minSeverity`。
- `matched`：规则可评估且条件命中。
- `not_matched`：规则可评估但条件未命中，包括 severity 未达到 `minSeverity`、threshold 正常评估但未命中。
- `skipped`：规则不适用于该 event 或缺少评估输入，包括 event_type 不匹配、metric 缺失、metric 非数字、timeWindow 不支持。
- `error`：规则配置解析失败或评估异常。
- `ruleId` 指定时，该 rule 必须存在且 `enabled = true`，否则返回 400 且不写 decision。
- `ruleId` 为空时，只对该 event 执行所有 `enabled = true` 的 rules。
- `standardEventId` 必填；不提供“最近 100 条”默认扫描，不允许默认批量跑全库。
- 任何数据结构变更必须走 Flyway migration。

## 测试结果

- 后端 targeted：`cd backend; mvn -pl edsp-core -am "-Dtest=RuleDecisionRunnerTest,RuleEvaluationControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过。
- 后端 full：`cd backend; mvn -pl edsp-core -am test` 通过，`95 tests, 0 failures, BUILD SUCCESS`。
- 前端：`cd frontend; npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；仅有 Git autocrlf line-ending warning。
- 代码审查：已请求 review，P1 已修复；当前无未解决 P0/P1。

## 已知后续项

- `RuleService.create` 当前做 MVP 级结构校验；后续可加强 structured config schema 校验和错误提示。
- `alert_decisions` upsert 已兼容 H2 与 PostgreSQL 风格；生产库上线前建议补真实 PostgreSQL 集成验证。
- 后续可增加 rule evaluation audit run 表，但本轮未引入新运行批次表，避免扩大范围。
- `AGENTS.md` 的 Next Recommended Stage 可能仍滞后，后续可单独清理规则文档，不应混入功能阶段。

## 下一轮建议

下一阶段建议进入 `Alerts MVP`。

目标：

```text
matched alert_decisions
then create alerts
then keep notifications disabled
```

Alerts MVP 可以做：

- 只基于 `matched` 的 `alert_decisions` 创建最小 `alerts`。
- 保持幂等，避免同一 decision 重复创建 alert。
- 前端展示 alert 列表和基础状态。
- 不触发通知。
- 不接入 AI / XGBoost / Agent。

Alerts MVP 仍然不能做：

- notifications
- AI / XGBoost / Agent orchestration
- Kafka / Redis / ClickHouse
- 绕过 `raw_events` / `standard_events` / `alert_decisions` 直接生成 alerts
- 把 collection、rules、alerts、notifications、AI 混在同一阶段
