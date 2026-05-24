# 数据安全预警分析平台 Handoff

更新时间：2026-05-24
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Alert Lifecycle MVP`
- 最新 feature merge commit：`4c847f1 merge: alert lifecycle mvp`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for alert lifecycle mvp`
- 本轮分支：`codex/alert-lifecycle-mvp`
- 本轮结果：已完成最小告警生命周期闭环。Core alert 现在支持确认、指派、关闭和处理时间线；状态模型固定为 `open / acknowledged / closed`。本轮不触发通知、不实现 reopen / SLA / 升级 / 合并 / 抑制。

## 已完成能力

- 新增 `V14__alert_lifecycle_events.sql`，为 `alerts` 增加 `assigned_to`、`acknowledged_at`、`closed_at`，并新增 `alert_lifecycle_events`。
- 新增 Core lifecycle API：
  - `POST /api/core/alerts/{id}/acknowledge`
  - `POST /api/core/alerts/{id}/assign`
  - `POST /api/core/alerts/{id}/close`
  - `GET /api/core/alerts/{id}/timeline`
- `acknowledge` 只允许 `open -> acknowledged`，写 `acknowledged_at`，写 timeline。
- `assign` 不改变 `alerts.status`，只更新 `assigned_to`，写 `event_type = assigned` 的 timeline。
- `close` 只允许 `open / acknowledged -> closed`，必须有非空 `note`，写 `closed_at`，写 timeline。
- `closed` 为终态，不允许继续 acknowledge / assign / close。
- lifecycle 更新使用事务和条件更新，避免并发 stale update 破坏状态机。
- `GET /api/core/alerts` 返回 `assignedTo / acknowledgedAt / closedAt`，供前端列表和详情展示。
- 前端 `AlertsPage` 增加状态筛选 `all / open / acknowledged / closed`。
- 前端列表展示 `status / assignedTo / updatedAt`。
- 前端详情 drawer 展示 lifecycle timeline。
- 前端支持确认、指派、关闭弹窗；关闭必须填写 `note`；指派必须填写 `assignee`。
- 前端仍只允许 `open` alert 手动发送通知。

## 明确未做 / 禁止误解

- 未做 reopen。
- 未做 SLA。
- 未做告警升级。
- 未做告警合并 / 抑制。
- 未做自动通知。
- 未做通知重试队列。
- 未做通知编排。
- 未新增企业微信 / 飞书 / 短信 / 邮件真实适配器。
- 未接入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。
- 未修改 sync-once / scheduled sync / rule evaluation / alert generation / notification sending 语义。
- 未删除旧 `alert_notes`。
- 未删除旧 `/api/alerts` disposition API。
- 未修改 `AGENTS.md`。

## 当前关键边界

- Alert lifecycle 主线入口为 `/api/core/alerts/...`。
- 新 lifecycle 状态模型为 `open / acknowledged / closed`。
- `assign` 不改变 alert 状态。
- `closed` 是终态。
- 每次 acknowledge / assign / close 都必须写 `alert_lifecycle_events`。
- 每次 acknowledge / assign / close 都必须更新 `alerts.updated_at`。
- Notification MVP 边界保持不变：只有 `status = open` 的 alert 可以手动发送通知。
- 本轮 lifecycle 不写 `notification_deliveries`，不调用 notification service。
- 旧 `edsp-alert` disposition API 仍保留，可能存在 legacy `processing / resolved` 状态；Core lifecycle 不扩大旧状态语义。

## 测试结果

- 后端 core：`cd backend; mvn -pl edsp-core -am test` 通过，112 tests, 0 failures, BUILD SUCCESS。
- 后端 alert：`cd backend; mvn -pl edsp-alert -am test` 通过，17 tests, 0 failures, BUILD SUCCESS。
- 前端：`cd frontend; npm.cmd run build` 通过，仅有既有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；Git `autocrlf` line-ending warning 不阻塞。
- 代码审查：Alert Lifecycle MVP review 初审发现 2 个 P1；已修复并复查通过，最终无 P0/P1。

## 已知后续项

- 旧 `/api/alerts` disposition API 仍可写 legacy 状态，后续可单独规划兼容收口。
- 可补充 Spring proxy 级事务回滚测试，但当前 service 入口已使用 `@Transactional`，核心状态竞态已有回归测试。
- 生产 PostgreSQL 上线前建议再跑真实 PostgreSQL migration 验证。
- 生命周期稳定后可继续扩展 reopen、SLA、升级、合并 / 抑制，但应独立阶段处理。

## 下一轮建议

下一阶段建议进入 `Notification Channel Adapter MVP`。

建议范围：

- 保持 `alertId + channelId` 手动触发通知边界。
- 逐个增加企业微信 / 飞书 / 短信 / 邮件适配器。
- 每个适配器必须有明确 timeout、失败语义、脱敏和单元测试。
- 不做自动通知、不做重试队列、不做升级编排、不接入 AI / Kafka / Redis / ClickHouse。
