# 数据安全预警分析平台 Handoff

更新时间：2026-05-24
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Alerts MVP`
- 最新 feature merge commit：`2c6769b merge: alerts mvp`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for alerts mvp`
- 本轮分支：`codex/alerts-mvp`
- 本轮结果：已完成从 `matched alert_decisions` 手动生成最小 `alerts` 的 MVP。系统现在可以基于 `decisionId` 幂等创建 `status = open` 的 alert，并在前端展示 alert 列表和详情；本阶段不触发通知、不接入 AI、不自动从规则评估生成 alert。

## 已完成能力

- 新增 `V13__alert_generation_from_decisions.sql`。
- `alerts` 新增 nullable `alert_decision_id`，兼容历史旧 alert。
- 对非空 `alert_decision_id` 增加唯一约束，确保同一 decision 最多生成一个 alert。
- 新增 `AlertGenerationService`，唯一创建入口为 `decisionId`。
- 新增 `AlertRepository`，封装 alert 创建、幂等查询和 core alert list 查询。
- 新增 `POST /api/core/alert-generations/run`。
- 新增 `GET /api/core/alerts?limit=50&status=open&severity=high`。
- 只允许 `decision = matched` 的 `alert_decisions` 创建 alert。
- 非 matched decision、缺失 decision、缺失关联 `standard_event` 均返回错误且不写脏数据。
- 同一 `decisionId` 重跑返回 existing alert，不重复插入。
- alert 创建时固定 `status = open`。
- 前端 `AlertsPage` 改为使用 core alerts API。
- 前端支持输入 `Decision ID` 手动生成 alert。
- 前端展示 alert 列表、severity、status、ruleName、standardEventId、decisionId 和详情。

## 明确未做 / 禁止误解

- 未做 notifications。
- 未写 `notification_deliveries`。
- 未调用 notification service。
- 未调用旧 `/api/alerts/ingest`。
- 未调用 `RuleExecutionService`。
- 未从 Rule Evaluation 自动触发 `AlertGenerationService`。
- 未实现 alert 确认、关闭、指派、升级、抑制、合并、SLA 等 lifecycle 能力。
- 未做批量全库扫描。
- 未做实时流处理。
- 未接入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。
- 未修改 sync-once / scheduled sync 链路。
- 未绕过 `raw_events` / `standard_events` / `alert_decisions` 直接生成 alerts。

## 当前关键边界

- alert 只能从 `alert_decisions` 生成。
- `AlertGenerationService` 的唯一创建输入是 `decisionId`。
- `POST /api/core/alert-generations/run` 只接受 `decisionId` 字段；`standardEventId`、`ruleId`、`operatorName` 等额外字段会被拒绝。
- 只有 `decision = matched` 才能生成 alert。
- `alerts.alert_decision_id` 可以为空，用于兼容历史数据；非空时必须唯一。
- 本阶段只创建 `status = open` 的最小 alert。
- Alert lifecycle 应作为后续独立阶段处理，不应混入 Alerts MVP。
- Notification MVP 之前不得触发通知。

## 测试结果

- 后端 targeted：`cd backend; mvn -pl edsp-core -am "-Dtest=AlertGenerationServiceTest,AlertGenerationControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，8 tests, 0 failures。
- 后端 full：`cd backend; mvn -pl edsp-core -am test` 通过，103 tests, 0 failures, BUILD SUCCESS。
- 前端：`cd frontend; npm.cmd run build` 通过，仅有既有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；仅有 Git autocrlf line-ending warning。
- 代码审查：已请求 reviewer 审查。发现 1 个 P1（请求契约包含 `operatorName`），已修复并复查通过；当前无未解决 P0/P1。

## 已知后续项

- 生产 PostgreSQL 上线前建议再跑真实 PostgreSQL migration 验证。
- Alert lifecycle（确认、关闭、指派、抑制、SLA）尚未实现，应独立规划。
- Notification MVP 需要基于已生成 alerts 触发通知，但不得绕过 alerts 直接通知。
- 当前前端仅提供最小 alert 生成和列表展示，复杂工作台能力后续再做。

## 下一轮建议

下一阶段建议进入 `Notification MVP`。

目标：

```text
open alerts
then route to notification channels
then record notification deliveries
then keep AI / Kafka / Redis / ClickHouse out of scope
```

Notification MVP 可以做：

- 仅基于已存在 `alerts` 触发通知。
- 复用已有 notification channel / delivery 表和服务。
- 记录 notification delivery 结果。
- 前端展示通知发送状态和历史。

Notification MVP 仍然不能做：

- AI / XGBoost / Agent orchestration
- Kafka / Redis / ClickHouse
- 实时流处理
- 复杂告警生命周期工作台
- 绕过 `alerts` 直接从 `standard_events` 或 `alert_decisions` 发送通知
