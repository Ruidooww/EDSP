# 数据安全预警分析平台 Handoff

> 更新时间：2026-05-23
> 当前稳定分支：`master`
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Ingestion Plan Sync Once Hardening`
- 最新 feature merge commit：`2078a71 merge: ingestion plan sync once hardening`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for sync once hardening`
- 本轮分支：`codex/ingestion-plan-sync-once-hardening`
- 本轮结果：已完成 Sync Once 幂等边界和标准层数据边界加固，避免进入自动采集前放大跨源误判重复、标准层重复保存完整源行、物理表/字段缺失口径不清等风险。

## 已完成能力

- 已完成 Database Intelligence Ingestion Plan MVP、Shadow Validator MVP、Ingestion Plan Quality Hardening、Activation Gate MVP、Ingestion Plan Sync Once MVP，并合并到 `master`。
- 已完成 Ingestion Plan Sync Once Hardening。
- sync-once 仍只允许通过 active activation 执行，不提供绕过 activation 的 ingestion_plan 直连同步入口。
- sync-once 仍会校验 activation、plan、最新 passed shadow run 的关系。
- sync-once dedup key 已加入 `dataSourceId`、`schemaTableId` 和 source table namespace，避免不同数据源或不同表的相同业务 ID 被误判为同一条标准事件。
- `StandardEventDedupService` 在 sync-once 路径下按当前 `data_source_id` 限定查重范围，避免跨数据源误撞。
- 重复执行 sync-once 时，重复行只关联已有 `standard_events`，不覆盖既有标准事件内容。
- `standard_events.normalized_json` 只保留标准化内容，例如 `sourceTable` 和 `mapped`，不再保存完整 source row。
- 完整原始源行仍保留在 `raw_events.payload_json`，后续回溯通过 `standard_events.raw_event_id -> raw_events.payload_json`。
- source metadata inactive、物理源表缺失、物理源字段缺失均按 `blocked` 记录 sync run。
- dedup 字段缺失、severity 无法识别等行级坏数据按 warning 处理，不让整批失败。
- 已补充跨数据源、跨表、normalized payload、blocked/warning、重复执行不覆盖旧标准事件等测试。

## 明确未做 / 禁止误解

- 未做 scheduled sync。
- 未做 rule engine。
- 未写入 `alert_decisions`。
- 未写入 `alerts`。
- 未触发通知。
- 未引入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。
- 未新增 REST API。
- 未新增数据库表或状态。
- sync-once 仍是人工触发的一次性同步，不是正式定时采集。
- Activation 仍然是审计门禁记录，不是 `ingestion_plans.status = active`。

## 当前关键边界

- 不允许绕过 `raw_events` / `standard_events` 直接生成 alerts。
- 不允许没有 active activation 的方案执行正式 sync-once。
- warning / failed / blocked Shadow Run 不能启用方案。
- deactivated / missing activation 不能执行 sync-once。
- Sync Once 不能生成 `alert_decisions` 或 `alerts`。
- 通知链路必须等 Notification MVP。
- 后续 scheduled sync 必须复用 active activation 门禁，不能绕过 Activation Gate。
- 后续 scheduled sync 必须复用已加固的 sync-once read/write/dedup/partial failure 边界。
- 任何数据库结构变更必须走 Flyway migration。
- 修改接口后必须同步前端类型和页面调用。

## 测试结果

- 后端：`cd backend; mvn -pl edsp-core -am test` 通过，`75 tests, 0 failures, BUILD SUCCESS`。
- 前端：`cd frontend; npm.cmd run build` 通过；仅有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；仅有 Git `autocrlf` line-ending warning，不阻塞。
- 分支检查：`git rev-list --left-right --count origin/codex/ingestion-plan-sync-once-hardening...HEAD` 为 `0 0`，阶段分支已推送并与本地一致。

## 已知后续项

- SQL Server / PostgreSQL 真实库集成测试后续补充。
- sync-once 当前是人工触发，不包含调度器。
- 生产环境建议为 sync run、raw/standard 幂等键补充更强数据库约束和监控指标。
- 后续可以继续拆分 `IngestionPlanSyncOnceService`，但不应在 hardening 阶段扩大重构范围。
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
