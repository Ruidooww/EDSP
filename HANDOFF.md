# 数据安全预警分析平台 Handoff

更新时间：2026-05-25
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Delivery Hardening MVP`
- 最新 feature merge commit：`48c3156 merge: notification delivery hardening mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification delivery hardening mvp`
- 本轮阶段分支：`codex/notification-delivery-hardening-mvp`
- 本轮结果：已完成通知投递记录只读排查能力增强，支持按现有字段组合筛选 `notification_deliveries`，并保持现有手动通知边界不变。

## 已完成能力

- `GET /api/notifications/deliveries` 保留现有 `limit`、`alertId` 查询参数。
- `GET /api/notifications/deliveries` 新增 `status` 查询参数，仅允许 `success` / `failed`。
- `GET /api/notifications/deliveries` 新增 `channelType` 查询参数，仅允许 `webhook` / `wecom` / `feishu`。
- `GET /api/notifications/deliveries` 新增 `channelId` 查询参数。
- `alertId + status + channelType + channelId` 支持组合查询。
- 非法 `status` 返回 400，错误标识为 `invalid_delivery_status`。
- 非法 `channelType` 返回 400，错误标识为 `unsupported_channel`。
- `limit` 继续保持默认值和最大值保护。
- `NotificationsPage` 投递记录区域新增 `status`、`channelType`、`channelId` 筛选。
- `NotificationsPage` 保留按 `alertId` 查询投递记录。
- `NotificationsPage` 使用查询参数组合加载投递记录。
- 投递明细继续只展示已脱敏的 `response_body` / `payload_json` 预览。
- 通知通道表格列标题统一为“最近状态”。
- 通道名称 placeholder 为“例如：安全运营 Webhook、企业微信、飞书”。
- 前端不再展示尚未支持的邮件、短信通道标签。

## 明确未做 / 禁止误解

- 未修改 `POST /api/notifications/alerts/send`。
- 未新增通知发送入口。
- 未新增失败原因字段。
- 未新增数据库表。
- 未新增 migration。
- 未新增通知通道适配器。
- 未做 Email Adapter。
- 未做 SMS Adapter。
- 未做飞书签名校验。
- 未做 Lark 国际版。
- 未做自动通知。
- 未做 `channelId` 为空群发。
- 未做重试队列。
- 未做失败重放。
- 未做告警升级。
- 未做通知编排。
- 未修改 alert lifecycle。
- 未写 `alert_lifecycle_events`。
- 未修改 `alerts` / `alert_decisions` / `standard_events` 语义。
- 未修改 sync-once / scheduled sync / rule evaluation / alert generation 语义。
- 未引入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。

## 当前关键边界

- Notification delivery 仍只能从既有 `alerts` 出发。
- 手动通知入口仍为 `POST /api/notifications/alerts/send`。
- 手动发送请求体仍只允许 `alertId + channelId`。
- alert 必须存在且 `status = open`。
- notification channel 必须 `enabled = true`。
- delivery 查询接口是只读排查能力，不触发真实 HTTP 请求。
- delivery 查询接口不得写 `alert_lifecycle_events`。
- Notification code 不得修改 alert lifecycle 状态。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery service。
- 新增通知排查能力不得绕过 `alerts` 直接从 `standard_events`、`alert_decisions` 或 raw payload 发送通知。

## 测试结果

- 后端 alert：`cd backend; mvn -pl edsp-alert -am test` 通过，46 tests，0 failures，BUILD SUCCESS。
- 后端 core：`cd backend; mvn -pl edsp-core -am test` 通过，112 tests，0 failures，BUILD SUCCESS。
- 前端：`cd frontend; npm.cmd run build` 通过，仅有既有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；Git `autocrlf` line-ending warning 不阻塞。
- 代码审查：Notification Delivery Hardening MVP review 已完成；最初发现 1 个 P2 controller 400 测试缺口，已补齐并复审通过；当前无 P0 / P1 / P2 遗留。

## 已知后续项

- 如果要按真实失败原因筛选，需要单独阶段通过 migration 增加结构化失败原因字段。
- 如果要做失败重试、失败重放、告警升级或通知编排，需要单独阶段设计状态流和幂等边界。
- 如果继续扩展通知通道，每个适配器仍必须独立定义 URL 校验、timeout、失败语义、脱敏和 mock HTTP 测试。
- 通知排查页面后续可以在有结构化字段后增加失败原因汇总和统计视图。

## 下一轮建议

下一阶段建议进入 `Notification Delivery Reliability MVP`。

建议范围：

- 继续保持手动通知入口和 `alertId + channelId` 边界。
- 为 `notification_deliveries` 增加结构化失败原因、可重放标记或最小重试记录前，先明确 migration 和状态模型。
- 不做自动通知，不做全量群发，不做告警升级，不做通知编排，不接入 AI / Kafka / Redis / ClickHouse。

备选方向：

- 如果优先扩展通道，可单独进入下一个 `Notification Adapter MVP`，例如邮件或短信，但不得混入重试、升级、编排能力。
