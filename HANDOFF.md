# 数据安全预警分析平台 Handoff

> 更新时间：2026-05-23
> 当前稳定分支：`master`
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Scheduled Sync MVP`
- 最新 feature merge commit：`499390a merge: scheduled sync mvp`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for scheduled sync mvp`
- 本轮分支：`codex/scheduled-sync-mvp`
- 本轮结果：已完成基于 active activation 的最小定时同步能力，scheduled sync 复用已加固的 sync-once 核心读写链路，写入 `raw_events` / `standard_events` 并记录 scheduled sync run，全程不生成告警、不触发通知。

## 已完成能力

- 已完成 Database Intelligence Ingestion Plan MVP、Shadow Validator MVP、Ingestion Plan Quality Hardening、Activation Gate MVP、Ingestion Plan Sync Once MVP、Ingestion Plan Sync Once Hardening，并合并到 `master`。
- 已完成 Scheduled Sync MVP。
- 新增 `ingestion_plan_sync_schedules` 调度表，使用 `enabled` / `paused` 表示调度状态，避免和 activation 的 `active` 状态混淆。
- 扩展 `ingestion_plan_sync_runs`，新增 `schedule_id` 和 `trigger_type`，manual sync-once 写 `manual`，scheduled sync 写 `scheduled`。
- 同一 activation 在 MVP 中最多只能存在一个 sync schedule，后续通过 update / pause / resume 管理。
- 创建 schedule 必须校验 activation 为 `active`、plan 仍为 `approved` / `shadow_ready`、activation 关联的是最新且 `passed` 的 Shadow Run。
- 新增 schedule API：创建、按 plan 查询、更新、暂停、恢复。
- 新增 Spring `@Scheduled` 轮询器，且默认不启动，必须显式设置 `edsp.ingestion-plan.scheduler.enabled=true`。
- scheduler 只扫描 `enabled`、due、未锁定且 activation 仍为 `active` 的 schedule。
- claim schedule 时会二次校验 due、未锁定、`enabled` 且 activation 仍为 `active`，避免 stale due 或 activation 刚停用后的误执行。
- scheduled sync 复用 sync-once 的 source reading、raw write、standard write、dedup 和 partial failure handling，不复制第二套写入逻辑。
- scheduled sync 执行后无论结果为 `passed` / `warning` / `blocked` / `failed`，都会更新 `last_run_at` 并推进 `next_run_at`。
- `passed` / `warning` 会重置 `consecutive_failures`，`blocked` / `failed` 会递增 `consecutive_failures`。
- pause 只改 `status = paused`，不执行、不清空 `next_run_at`。
- resume 改 `status = enabled`，并将 `next_run_at` 设置为当前时间，方便恢复后尽快执行。
- 前端在推荐接入方案区域展示定时同步配置、状态、最近 scheduled run，并支持创建、暂停、恢复和更新 interval/sample limit。
- 前端文案明确 scheduled sync 会写入 `raw_events` / `standard_events`，不会生成 `alert_decisions` / `alerts`，不会触发通知。

## 明确未做 / 禁止误解

- 未做 rule engine。
- 未写入 `alert_decisions`。
- 未写入 `alerts`。
- 未触发通知。
- 未引入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。
- 未引入 Quartz 或其他新调度依赖。
- 未做分布式锁；当前为单实例 MVP。
- 未允许前端传入自定义 SQL、自定义表名或自定义字段名来执行调度。
- 未绕过 active activation 执行 scheduled sync。
- 未修改 `AGENTS.md`。

## 当前关键边界

- 不允许绕过 `raw_events` / `standard_events` 直接生成 alerts。
- 不允许没有 active activation 的方案执行 sync-once 或 scheduled sync。
- warning / failed / blocked Shadow Run 不能启用方案。
- deactivated / missing activation 不能创建、更新、恢复或执行 schedule。
- Scheduled Sync 不能生成 `alert_decisions` 或 `alerts`。
- Scheduled Sync 不能触发通知。
- scheduled sync 必须复用 sync-once 已加固的 read/write/dedup/partial failure 边界，不能维护第二套写入逻辑。
- scheduler 执行时必须复用 activation / plan / schema metadata 中已确认的配置，不能接受临时 SQL 或临时表字段配置。
- 任何数据库结构变更必须走 Flyway migration。
- 修改接口后必须同步前端类型和页面调用。

## 测试结果

- 后端 targeted：`cd backend; mvn -pl edsp-core -am "-Dtest=IngestionPlanSyncOnceServiceTest,IngestionPlanSyncScheduleControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，`26 tests, 0 failures, BUILD SUCCESS`。
- 后端 full：`cd backend; mvn -pl edsp-core -am test` 通过，`84 tests, 0 failures, BUILD SUCCESS`。
- 前端：`cd frontend; npm.cmd run build` 通过；仅有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error。
- 代码审查：已请求 review，结论无 P0 / P1；两个 P2 已处理。

## 已知后续项

- 生产多实例部署前，需要补充分布式锁或数据库级 claim/lease 策略。
- SQL Server / PostgreSQL 真实库 scheduled sync 集成测试后续补充。
- 生产环境建议为 sync run、raw/standard 幂等键补充更强数据库约束和监控指标。
- 后续可以继续拆分 `IngestionPlanSyncOnceService`，但不应在 scheduled sync 阶段扩大重构范围。
- `AGENTS.md` 中的 Next Recommended Stage 可能仍滞后，后续可单独做一次规则文档清理，不应混入功能阶段。

## 下一轮建议

下一阶段建议进入 `Rule Decision MVP`。

目标：

```text
standard_events
then rule decision
then record decision result
then do not trigger notifications
```

Rule Decision MVP 可以做：

- 基于已有 `standard_events` 执行最小规则判断。
- 记录规则决策结果。
- 保持与 sync-once / scheduled sync 解耦，不能在采集链路里直接生成 alerts。
- 前端展示规则决策结果和基础命中信息。

Rule Decision MVP 仍然不能做：

- notifications
- AI / XGBoost / Agent orchestration
- Kafka / Redis / ClickHouse
- 绕过 `raw_events` / `standard_events` 直接生成 alerts
- 把 collection、rules、alerts、notifications、AI 混在同一阶段
