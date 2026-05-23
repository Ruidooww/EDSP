# 数据安全预警分析平台 Handoff

> 更新时间：2026-05-23
> 当前稳定分支：`master`
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Ingestion Plan Sync Once MVP`
- 最新 feature merge commit：`379f4f7 merge: ingestion plan sync once`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for ingestion plan sync once`
- 本轮分支：`codex/ingestion-plan-sync-once`
- 本轮结果：已完成 active activation 驱动的手动同步一次能力，可以读取源表、写入 `raw_events` / `standard_events`，并记录 sync run；本轮不生成告警、不触发通知。

## 已完成能力

- 已完成 Database Intelligence Ingestion Plan MVP、Shadow Validator MVP、Ingestion Plan Quality Hardening、Activation Gate MVP，并合并到 `master`。
- 已完成 Ingestion Plan Sync Once MVP。
- 新增 sync-once 后端入口，只允许通过 active activation 执行，不提供绕过 activation 的 ingestion_plan 直连同步入口。
- sync-once 会重新校验 activation、plan、最新 passed shadow run 的关系。
- sync-once 读取已扫描确认的 source metadata 和 plan mapping，不接受前端传入任意 SQL / 表名 / 字段名。
- sync-once 写入 `raw_events`。
- sync-once 按 `fieldMappings` 标准化写入 `standard_events`。
- sync-once 按 dedup 策略做幂等，重复执行不会重复写入同一标准事件。
- 行级坏数据按 warning 处理，不让整批失败。
- 新增 `ingestion_plan_sync_runs` 记录 sync-once 结果、统计和 report。
- 前端在推荐接入方案区域展示“手动同步一次”和最近同步结果。

## 明确未做 / 禁止误解

- 未做 scheduled sync。
- 未做 rule engine。
- 未写入 `alert_decisions`。
- 未写入 `alerts`。
- 未触发通知。
- 未引入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。
- sync-once 是人工触发的一次性同步，不是正式定时采集。
- Activation 仍然是审计门禁记录，不是 `ingestion_plans.status = active`。

## 当前关键边界

- 不允许绕过 `raw_events` / `standard_events` 直接生成 alerts。
- 不允许没有 active activation 的方案执行正式 sync-once。
- warning / failed / blocked Shadow Run 不能启用方案。
- deactivated / missing activation 不能执行 sync-once。
- Sync Once MVP 不能生成 `alert_decisions` 或 `alerts`。
- 通知链路必须等 Notification MVP。
- 后续 scheduled sync 必须复用 active activation 门禁，不能绕过 Activation Gate。
- 任何数据库结构变更必须走 Flyway migration。
- 修改接口后必须同步前端类型和页面调用。

## 测试结果

- 后端：`cd backend; mvn -pl edsp-core -am test` 通过，`65 tests, 0 failures, BUILD SUCCESS`。
- 前端：`cd frontend; npm.cmd run build` 通过；仅有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；`git status --short --branch` 在提交前仅包含本轮分支提交状态，未纳入无关文件。

## 已知后续项

- SQL Server / PostgreSQL 真实库集成测试后续补充。
- sync-once 当前是人工触发，不包含调度器。
- 生产环境建议为 sync run、raw/standard 幂等键补充更强数据库约束和监控指标。
- 后续可以继续拆分 `IngestionPlanSyncOnceService`，但不应在本阶段扩大重构范围。
- 后续可补充更细粒度的 standardization transform rule 和字段类型归一化策略。

## 下一轮建议

下一阶段建议进入 `Scheduled Sync MVP`。

目标：

```text
active activation
then scheduled sync trigger
then reuse sync-once read/write path
then record scheduled sync run
then do not generate alerts
```

Scheduled Sync MVP 可以做：

- 基于 active activation 配置最小定时同步。
- 复用 sync-once 的 source reading、raw write、standard write、dedup 和 partial failure handling。
- 记录 scheduled sync run。
- 前端展示启停和最近调度结果。

Scheduled Sync MVP 仍然不能做：

- rule engine
- `alert_decisions`
- `alerts`
- notifications
- AI / XGBoost / Agent orchestration
- Kafka / Redis / ClickHouse
