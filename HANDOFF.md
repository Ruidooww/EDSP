# 数据安全预警分析平台 Handoff

更新时间：2026-05-24
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Channel Adapter MVP`
- 最新 feature merge commit：`e7a63ff merge: notification channel adapter mvp`
- 最新 HANDOFF docs commit：当前提交 `docs: update handoff for notification channel adapter mvp`
- 本轮分支：`codex/notification-channel-adapter-mvp`
- 本轮结果：已完成企业微信群机器人 Webhook 适配器，并保持现有手动通知边界；发送入口仍为 `POST /api/notifications/alerts/send`，请求仍只允许 `alertId + channelId`。

## 已完成能力

- 新增通知通道适配器接口与注册表：`NotificationChannelAdapter`、`NotificationChannelAdapterRegistry`。
- 将原有普通 `webhook` 发送逻辑收口到 `WebhookNotificationAdapter`。
- 新增 `WeComNotificationAdapter`，支持 `channel_type = wecom`。
- 企业微信机器人 URL 校验固定为：
  - scheme 必须为 `https`
  - host 必须为 `qyapi.weixin.qq.com`
  - path 必须包含 `/cgi-bin/webhook/send`
  - query 必须包含非空 `key`
- 企业微信通知 payload 使用 markdown 格式。
- 企业微信发送结果语义固定为：
  - HTTP 2xx 且 `response.errcode = 0` 才算 `success`
  - HTTP 2xx 但 `errcode != 0` 算 `failed`
  - HTTP 非 2xx、timeout、IOException、malformed response 均算 `failed`
- 企业微信 key 已做脱敏保护，不应原样出现在 `response_body`、`error_message`、`notification_deliveries.payload_json`、`endpoint_masked`。
- `config_json` 不重复保存企业微信 key 或完整 webhook URL。
- 前端 `NotificationsPage` 支持创建 `wecom` 类型通知通道。
- 前端 `AlertsPage` 手动发送通知通道选择支持 `webhook / wecom`。
- 后端测试使用 mock HTTP client，不真实请求公网。

## 明确未做 / 禁止误解

- 未做自动通知。
- 未做通知重试队列。
- 未做告警升级编排。
- 未做飞书 / 短信 / 邮件真实适配器。
- 未修改 alert lifecycle。
- 未写 `alert_lifecycle_events`。
- 未修改 sync-once / scheduled sync / rule evaluation / alert generation 语义。
- 未绕过 `alerts` 直接从 `standard_events`、`alert_decisions` 或 raw payload 发送通知。
- 未修改 `AGENTS.md`。

## 当前关键边界

- Notification delivery 仍只能从既有 `alerts` 出发。
- 手动通知入口仍为 `POST /api/notifications/alerts/send`。
- 请求体仍只允许 `alertId + channelId`。
- alert 必须存在且 `status = open`。
- notification channel 必须 `enabled = true`。
- Notification code 不得修改 alert lifecycle 状态。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery service。
- 企业微信 key 不得落入日志、响应、delivery payload 或前端 masked endpoint。

## 测试结果

- 后端 alert：`cd backend; mvn -pl edsp-alert -am test` 通过，27 tests，0 failures，BUILD SUCCESS。
- 后端 core：`cd backend; mvn -pl edsp-core -am test` 通过，112 tests，0 failures，BUILD SUCCESS。
- 前端：`cd frontend; npm.cmd run build` 通过，仅有既有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；Git `autocrlf` line-ending warning 不阻塞。
- 代码审查：Notification Channel Adapter MVP review 复审无 P0 / P1 / 阻塞 P2。

## 已知后续项

- 可以继续逐个补充飞书、邮件、短信等通知适配器；每个适配器必须有独立 timeout、失败语义、脱敏和 mock HTTP 测试。
- 可以后续单独规划通知重试队列、失败重放、升级编排，但不应混入适配器扩展阶段。
- 如需更多外部通道，仍需保持 `alertId + channelId` 手动发送边界，除非新阶段明确改变通知触发模型。

## 下一轮建议

下一阶段建议进入 `Notification Channel Adapter Expansion MVP`。

建议范围：
- 继续在 `edsp-alert` 内逐个增加外部通知适配器，例如飞书或邮件。
- 保持手动通知入口和 `alertId + channelId` 边界。
- 不做自动通知、不做重试队列、不做升级编排、不接入 AI / Kafka / Redis / ClickHouse。
