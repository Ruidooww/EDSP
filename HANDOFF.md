# 数据安全预警分析平台 Handoff

更新时间：2026-05-25
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Security Operations Dashboard MVP`
- 最新 feature merge commit：`ce30e59 merge: security operations dashboard mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for security operations dashboard mvp`
- 本轮阶段分支：`codex/security-operations-dashboard-mvp`
- 本轮结果：已完成只读安全运营总览能力，前端 Dashboard 可通过唯一 overview 入口展示告警、处置、通知、数据源、规则和报表概览。

## 已完成能力

- 新增核心只读 overview 入口：
  - `GET /api/core/overview`
- `OverviewController` 聚合只读查询：
  - `alerts`
  - `alert_lifecycle_events`
  - `notification_channels`
  - `notification_deliveries`
  - `data_sources`
  - `schema_scan_runs`
  - `ingestion_plans`
  - `rules`
  - `reports`
- Dashboard 增加安全运营视图：
  - 风险与告警概览
  - 告警生命周期概览
  - 通知投递概览
  - 通知通道健康概览
  - 数据源 / Schema / 接入方案概览
  - 规则与报表概览
  - 最近数据源与最近处置事件摘要
- `frontend/src/types.ts` 增加 overview 数据结构，支撑 Dashboard 类型检查。
- `frontend/src/App.tsx` 接入 Dashboard 页面路由。
- `OverviewControllerTest` 覆盖：
  - overview 返回安全运营与通知投递摘要
  - 空表场景返回安全默认值
  - overview 查询不修改 operational tables 和 alert status

## 明确未做 / 禁止误解

- 未新增 migration。
- 未写入 `notification_deliveries`。
- 未写入 `alert_lifecycle_events`。
- 未修改 `alerts.status`。
- 未调用 notification send service。
- 未调用 notification retry service。
- 未调用 alert lifecycle mutation service。
- Dashboard 未提供“重试一次”按钮。
- Dashboard 未展示完整 `payload_json`。
- Dashboard 未展示完整 `response_body`。
- 未修改 notification sending 语义。
- 未修改 alert lifecycle 语义。
- 未修改 sync-once / scheduled sync / rule evaluation / alert generation 语义。
- 未修改 `AGENTS.md`。
- 未引入 Kafka / Redis / ClickHouse / AI。

## 当前关键边界

- `GET /api/core/overview` 是当前唯一 overview 入口。
- Security Operations Dashboard 只做只读聚合，不承载 mutation 操作。
- Dashboard 不得绕过 Alerts / Notifications / Lifecycle 现有页面和 API 执行业务动作。
- Notification delivery 仍只能从已有 `alerts` 出发。
- 正常通知发送入口仍是 `POST /api/notifications/alerts/send`。
- retry 入口仍只能是 `POST /api/notifications/deliveries/{id}/retry`，Dashboard 不提供 retry 操作。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。
- Rule evaluation 不得直接创建 alerts 或发送 notifications。
- Alert generation 只能从 matched `alert_decisions` 创建 alerts。

## 测试结果

- 阶段分支验证：
  - `mvn -pl edsp-core -am -Dtest=OverviewControllerTest "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，`Tests run: 3, Failures: 0, Errors: 0`
  - `mvn -pl edsp-core -am test` 通过，`Tests run: 115, Failures: 0, Errors: 0`
  - `mvn -pl edsp-alert -am test` 通过，`Tests run: 54, Failures: 0, Errors: 0`
  - `npm.cmd run build` 通过，仅有 Vite chunk size warning
  - `git diff --check` 通过，无 whitespace error
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮未做浏览器交互 smoke test；如后续需要，可单独启动前端验证 Dashboard 实际渲染与导航。
- Dashboard 当前是总览层，仍建议保持只读；具体处置、通知发送、retry 等动作继续留在各自业务页面。
- 如后续运营数据增多，可单独规划 Dashboard drill-down、时间范围筛选、趋势图或权限视图，但不得混入通知发送或生命周期 mutation。
- GitHub Actions branch run 需要在远端完成后观察结果；本地未安装 `gh`，无法直接查询 Actions run。

## 下一轮建议

建议下一阶段优先做 `Security Operations Dashboard Hardening MVP` 或 `Alert Operations Drill-down MVP`，继续保持 Dashboard 只读边界。

可选方向：

- `Security Operations Dashboard Hardening MVP`
  - 增加时间范围筛选
  - 增加风险趋势和通知失败趋势
  - 增加 dashboard browser smoke test / visual check
  - 不做 retry、不做通知发送、不做 lifecycle mutation
- `Alert Operations Drill-down MVP`
  - 从告警处置视图进入关联通知投递记录
  - 提升 alerts 列表和详情页可读性
  - 仍保持通知只手动触发，retry 只在 NotificationsPage 中执行

下一阶段仍必须遵守：

```text
One stage.
One branch.
One scope.
One full verification.
One merge.
One post-merge HANDOFF update.
Clean master before continuing.
```
