# 数据安全预警分析平台 Handoff

更新时间：2026-05-24
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification MVP`
- 最新 feature merge commit：`ec71724 merge: notification mvp`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for notification mvp`
- 本轮分支：`codex/notification-mvp`
- 本轮结果：已完成基于现有 `open alerts` 的手动 Webhook 通知 MVP。系统现在只能通过 `alertId + channelId` 手动触发通知，alert 必须存在且 `status = open`，channel 必须是启用状态的 `webhook` 通道；发送结果会写入 `notification_deliveries` 并在前端展示。

## 已完成能力

- 新增 `AlertNotificationService`，统一处理基于 alert 的手动通知发送。
- 新增 `WebhookClient` 和 `WebhookDeliveryResult`，执行真实 HTTP Webhook 发送。
- 新增 `AlertNotificationSendRequest`，通知请求契约只允许 `alertId` 和 `channelId`。
- 新增 `POST /api/notifications/alerts/send`。
- 旧 `POST /api/notifications/send` 已禁用，返回 `use_alert_notification_endpoint`。
- 旧 `POST /api/notifications/channels/{id}/test` 已禁用，返回 `use_alert_notification_endpoint`。
- alert 不存在返回 `alert_not_found`。
- alert 非 `open` 返回 `alert_not_open`。
- channel 不存在返回 `channel_not_found`。
- channel 未启用返回 `channel_disabled`。
- 非 `webhook` channel 返回 `unsupported_channel`，不写 demo success delivery。
- Webhook URL 必须是有效 `http` / `https` 绝对地址。
- Webhook 发送设置 connect timeout 和 request timeout。
- HTTP 2xx 写入 `success` delivery。
- HTTP 非 2xx、超时、连接异常、非法 URL 写入 `failed` delivery。
- `response_body` / `error_message` 会截断保存，并避免记录 Webhook secret。
- `RuleExecutionService` 中的 notify action 不再自动发送通知，仅记录 `automatic_notification_disabled`。
- demo seed 已调整：非 Webhook 通道标记为后续扩展，不再制造非 Webhook 假成功投递。
- 前端 `AlertsPage` 支持对 `open` alert 手动选择 Webhook 通道发送通知。
- 前端 `NotificationsPage` 展示通知通道和投递记录，去掉通道测试入口。

## 明确未做 / 禁止误解

- 未做自动通知。
- 未做定时通知。
- 未做重试队列。
- 未做告警升级。
- 未做通知编排。
- 未做通知模板系统。
- 未支持企业微信、飞书、短信、邮件的真实发送。
- 未允许 channelId 为空时群发全部 enabled channels。
- 未允许通过 `title` / `message` / `severity` / `decisionId` / `standardEventId` / `ruleId` 绕过 alerts 构造通知。
- 未绕过 `alerts` 直接从 `standard_events` 或 `alert_decisions` 发送通知。
- 未接入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。

## 当前关键边界

- Notification MVP 的唯一发送入口是 `POST /api/notifications/alerts/send`。
- 请求体只允许 `alertId` 和 `channelId`。
- `alertId` 必须指向已存在的 alert。
- alert 必须是 `status = open`。
- `channelId` 必填，第一版不支持空 channelId 群发。
- channel 必须是 enabled webhook。
- 非 webhook channel 第一版明确不支持，返回 `unsupported_channel`。
- Webhook 投递只根据真实 HTTP 结果写入 `success` 或 `failed`。
- `shadow_ready`、规则匹配、AI、报表任务均不得绕过 alert 通知边界。

## 测试结果

- 后端 alert：`cd backend; mvn -pl edsp-alert -am test` 通过，17 tests, 0 failures, BUILD SUCCESS。
- 后端 core：`cd backend; mvn -pl edsp-core -am test` 通过，103 tests, 0 failures, BUILD SUCCESS。
- 前端：`cd frontend; npm.cmd run build` 通过，仅有既有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error。
- 代码审查：Notification MVP review 通过；最终只读 reviewer 未发现 P0/P1/P2 边界违规。

## 已知后续项

- `WebhookClientTest` 当前覆盖 URL 拒绝、secret 脱敏和截断；真实 2xx、非 2xx、timeout、IOException 分支后续可通过可注入 `HttpClient` 增强单测。
- 生产 PostgreSQL 上线前建议再跑真实 PostgreSQL migration 验证。
- Alert lifecycle（确认、关闭、指派、抑制、SLA）尚未实现，应独立规划。
- 非 Webhook 通知通道（企业微信、飞书、短信、邮件）需要独立适配器阶段处理。
- 通知重试、告警升级、通知编排需要等基础发送闭环稳定后再设计。

## 下一轮建议

下一阶段建议进入 `Notification Channel Adapter MVP` 或 `Alert Lifecycle MVP`，二选一推进。

如果优先补通知通道：

- 保持 `alertId + channelId` 手动触发边界。
- 逐个增加企业微信、飞书、短信、邮件适配器。
- 每个适配器必须有明确超时、失败语义、脱敏和单元测试。

如果优先补告警生命周期：

- 增加 alert 确认、关闭、指派、备注和处理记录。
- 通知发送仍只能基于 `open alerts`，不得绕过 alert。
- 不引入 AI / Kafka / Redis / ClickHouse。
