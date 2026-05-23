# 数据安全预警分析平台 Handoff

> 更新时间：2026-05-23
> 当前分支：`codex/ingestion-plan-activation-gate`
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`
> 当前阶段：`Ingestion Plan Activation Gate MVP / 启用门禁 MVP`

## 1. 当前状态

`master` 已合并到以下提交：

```text
3b89be0 merge: ingestion plan quality hardening
```

当前本地工作分支为 `codex/ingestion-plan-activation-gate`。本轮文档交接以 Quality Hardening 合并后的代码为基线，下一阶段进入 Ingestion Plan Activation Gate MVP / 启用门禁 MVP。

Shadow Validator MVP 与 Quality Hardening 已合并。当前能力已经从早期的轻量 Shadow Precheck 扩展到可执行 Shadow Run 的 MVP：可以读取样本、生成校验报告、保存 shadow run 结果，并在前端展示报告。但这仍然是试运行验证能力，不是正式接入启用。

## 2. 产品主线

本项目是“数据安全预警分析平台”，不是 IP-Guard 专用平台，也不是单纯数据库读取工具。

产品主线仍保持为：

```text
外部系统 / 数据库 / API / Webhook / 文件 / Syslog / Agent
  -> 采集适配器
  -> raw_events / raw_logs / raw_imports
  -> 字段映射与标准化
  -> standard_events
  -> 规则 / 风险判断
  -> alerts
  -> 通知 / 处置 / 报表 / 反馈
```

当前阶段从 Ingestion Plan 的质量、可解释性和试运行验证，推进到启用门禁的审计记录设计。Activation 是“允许后续采集链路引用该方案”的门禁记录，不等于正式采集已经开始，也不直接写入正式事件或告警链路。

## 3. 本阶段已完成

### 3.1 Ingestion Plan 质量硬化

已完成并合并的能力包括：

- 从 `schema_tables` / `schema_fields` / `field_mappings` 生成 `ingestion_plans`。
- 保留人工字段映射，不用自动识别覆盖人工确认结果。
- 只对 `alert_table` / `log_table` 生成主接入方案候选。
- `plan_json.mode` 固定为 `database_polling`，模板类型放在 `templateMatch.templateKey`。
- `external_id` 不是绝对必填；没有外部 ID 时可尝试组合 dedup。
- `USER_ID` / `ASSET_ID` / `HOST_ID` 等主体或资产 ID 不作为外部事件 ID。
- `LOGIN_NAME` 等字段不会因为 substring 命中 `log` 而误触发 `log_table`。
- `coverage_unknown` 已落地；没有可靠 scan coverage 时会进入风险和保守推荐，不再作为未完成项记录。
- `suggested` / `review_required` 可更新；`approved` / `shadow_ready` 不被重新生成覆盖；`rejected` 不自动复活。

### 3.2 Shadow Precheck

`POST /api/core/ingestion-plans/{id}/shadow-validate` 已保留为试运行前校验入口。

它只返回检查报告，不写入 `raw_events`、`standard_events`、`alert_decisions` 或 `alerts`，也不触发通知，不改变方案状态。

### 3.3 Shadow Validator MVP

Shadow Validator MVP 已完成并合并，核心文件包括：

```text
backend/edsp-core/src/main/java/com/edsp/core/service/IngestionPlanShadowRunService.java
backend/edsp-core/src/main/java/com/edsp/core/service/JdbcShadowSampleService.java
backend/edsp-core/src/main/java/com/edsp/core/controller/IngestionPlanShadowRunController.java
backend/edsp-core/src/main/resources/db/migration/V8__ingestion_plan_shadow_runs.sql
frontend/src/pages/schema/components/IngestionPlanShadowRunReportDrawer.tsx
```

新增/保留的主要接口：

```text
POST /api/core/ingestion-plans/{id}/shadow-runs
GET  /api/core/ingestion-plans/{id}/shadow-runs?limit=
GET  /api/core/ingestion-plan-shadow-runs/{runId}
POST /api/core/ingestion-plans/{id}/shadow-validate
```

MVP 行为边界：

- 只允许 `approved` / `shadow_ready` 方案执行 Shadow Run。
- Shadow Run 会先执行 precheck；precheck blocked 时保存 blocked 报告。
- 样本读取通过 `JdbcShadowSampleService` 完成，`sampleLimit` 仍受上限约束。
- 报告保存到 `ingestion_plan_shadow_runs.report_json`。
- 统计 `read_count`、`success_count`、`failed_count`、`duplicate_count`、`missing_required_count`。
- 输出标准事件预览和逐项检查结果。
- 仍不写入 `standard_events`、`alert_decisions`、`alerts`，也不触发通知。

### 3.4 前端交互

前端已支持：

- 在推荐接入方案区域触发 Shadow Precheck。
- 在符合条件的方案上触发 Shadow Run。
- 查看 Shadow Run 列表和单次 run 报告。
- 展示标准事件预览、缺失字段、重复判定、阻断项、提醒项和检查项。
- `shadow_ready` 展示为试运行准备，不展示为正式启用。

## 4. 阶段边界

本阶段仍必须保留以下边界：

- 不把 `shadow_ready` 当成正式启用状态。
- 不从 Ingestion Plan / Shadow Validator 直接写入 `standard_events`。
- 不从 Ingestion Plan / Shadow Validator 直接生成 `alerts`。
- 不触发正式通知推送链路。
- 不引入 AI / XGBoost / 复杂 Agent。
- 不进入正式规则引擎闭环。
- 不做大重构；继续保持增量硬化和可验证的小步交付。
- 不绕过 raw 层直接生成告警。
- 不覆盖人工确认过的字段映射。
- 不让辅助表直接生成主接入方案。

说明：仓库里已有 core event pipeline 和相关表结构，但当前 Ingestion Plan Quality Hardening 阶段的 Shadow Validator MVP 只做验证和报告，不承担正式事件写入职责。

## 5. 状态机

当前方案状态白名单：

```text
suggested
review_required
approved
shadow_ready
rejected
```

允许流转：

```text
suggested       -> review_required
suggested       -> approved
suggested       -> rejected

review_required -> approved
review_required -> rejected

approved        -> shadow_ready
approved        -> rejected

shadow_ready    -> rejected
```

禁止：

```text
shadow_ready -> approved
```

状态含义：

- `approved` 表示方案已经人工批准，可以进入试运行验证。
- `shadow_ready` 表示方案已准备进入试运行阶段。
- `shadow_ready` 不是正式启用。
- `rejected` 是历史终态，不自动恢复为活动方案。

## 6. 已覆盖测试重点

当前合并内容的测试重点包括：

- Ingestion Plan 生成、状态流转、去重和人工映射保护。
- `coverage_unknown` 风险和保守推荐。
- `TemplateMatcherService` token 匹配与解释信号。
- Shadow Precheck 不写入 `standard_events` / `alerts`。
- Shadow Run 成功、blocked、重复、缺失必要字段和 coverage warning。
- Shadow Run 结果落到 `ingestion_plan_shadow_runs`，而不是正式事件表。
- 前端 Shadow Run 报告类型和展示组件。

## 7. 下一阶段：Ingestion Plan Activation Gate MVP / 启用门禁 MVP

下一阶段不要直接进入 alerts、通知或复杂规则引擎。当前目标是把“人工批准 + 最新 Shadow Run 通过证据”变成可审计的启用门禁记录，为后续 sync-once 做准备。

### 7.1 核心目标

Activation 是审计门禁记录，不等于正式采集启用。MVP 建议新增或使用 `ingestion_plan_activations` 记录启用门禁信息，包括 plan、shadowRunId、启用人、启用时间、启用依据、状态和停用信息。

本阶段不要新增 `ingestion_plans.status = active`。`ingestion_plans.status` 仍保持现有白名单，启用门禁状态由 activation 记录表达。

### 7.2 启用门槛

创建 active activation 前必须同时满足：

- plan 状态必须是 `approved` / `shadow_ready`。
- `shadowRunId` 必须属于当前 plan。
- `shadowRunId` 必须是当前 plan 最新一次 Shadow Run。
- 最新 Shadow Run status 必须是 `passed`。
- 最新 Shadow Run 为 `warning` / `blocked` / `failed` 时不能启用。

### 7.3 并发与唯一性

同一个 `ingestion_plan` 同一时间不能存在多个 active activation。

MVP 阶段先在服务层做防重：创建 active activation 前查询当前 plan 是否已有 active activation。后续进入生产库约束时，建议增加 active-only unique constraint，避免并发绕过服务层校验。

### 7.4 deactivate 边界

deactivate 只更新 `ingestion_plan_activations`，例如把当前 active activation 标记为 inactive / deactivated，并记录停用人、停用时间和原因。

deactivate 不修改 `ingestion_plans.status`，也不把 plan 回退到 `approved`、`shadow_ready` 或其他状态。

### 7.5 强边界

Activation Gate MVP 只建立门禁审计记录，不执行正式采集链路：

- 不写 `raw_events`。
- 不写 `standard_events`。
- 不写 `alert_decisions`。
- 不写 `alerts`。
- 不触发通知。
- 不进入规则引擎。
- 不进入 AI 判断链路。

### 7.6 后续阶段候选

#### sync-once 写 `raw_events`

后续阶段才考虑 sync-once 写 `raw_events`。目标是先验证一次性同步链路，把源表样本/增量行写入 raw 层。

候选内容：

- 只对已 activation 的方案开放。
- 按 plan 的 cursor / dedup 策略读取一小批数据。
- 写入 `raw_events`，保留 source payload、source metadata 和处理状态。
- 保证幂等，不重复写入同一 dedup key。
- 不在这个步骤直接产生 alerts。

#### sync-once 写 `standard_events`

目标是在 raw 层之后进行标准化落表。

候选内容：

- 从 `raw_events` 标准化生成 `standard_events`。
- 使用 plan 的 `fieldMappings` / `fieldMappingDetails` / transform rule。
- 复用或明确扩展 dedup 规则。
- 写入失败要能回溯到 raw event。
- 仍不进入规则引擎、alerts 或通知链路。

## 8. 交接提醒

- `coverage_unknown` 已落地，后续文档不要再把它列为待实现风险。
- Shadow Validator MVP 已完成并合并；后续方向是 Activation / sync-once，而不是回到验证器范围讨论。
- 后续若需要扩大 Shadow Validator 能力，必须先明确数据源读取权限、样本限制、脱敏策略和结果保留周期。
- 修改数据库结构必须走 Flyway migration。
- 修改接口后必须同步前端类型和页面调用。
- 提交前继续只纳入当前任务相关文件，避免把 `target/`、`node_modules/`、`dist/` 等生成目录加入仓库。
