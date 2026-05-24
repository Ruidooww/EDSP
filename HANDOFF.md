# 数据安全预警分析平台 Handoff

更新时间：2026-05-24
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Feishu Notification Adapter MVP`
- 最新 feature merge commit：`6759920 merge: feishu notification adapter mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for feishu notification adapter mvp`
- 本轮阶段分支：`codex/feishu-notification-adapter-mvp`
- 本轮结果：已完成 `channel_type = feishu` 飞书自定义机器人通知适配器，并保持现有手动通知边界。

## 已完成能力

- 新增 `FeishuNotificationAdapter`，接入现有 `NotificationChannelAdapter` / `NotificationChannelAdapterRegistry` 架构。
- `channelType()` 返回 `feishu`。
- `NotificationService` 允许 `channel_type = webhook | wecom | feishu`。
- `POST /api/notifications/channels` 支持创建飞书通道。
- 飞书 webhook URL 校验固定为：
  - scheme 必须是 `https`
  - host 必须是 `open.feishu.cn`
  - path 必须是 `/open-apis/bot/v2/hook/{token}`
  - token 必须非空且为单段 path，不允许额外 path segment
  - 不允许 query / fragment
- 飞书通知 payload 使用 text 消息格式：
  - `{"msg_type":"text","content":{"text":"..."}}`
- 飞书通知文本包含：
  - 告警标题
  - 等级
  - 规则
  - 主体
  - 资产
  - 发生时间
  - `alertId`
- 飞书发送结果语义：
  - HTTP 2xx 且响应 JSON `StatusCode == 0` 记为 `success`
  - HTTP 2xx 且响应 JSON `code == 0` 记为 `success`
  - HTTP 2xx 但 `StatusCode` / `code` 非 0 记为 `failed`
  - HTTP 非 2xx / timeout / IOException / malformed response 记为 `failed`
- 飞书失败 reason 固定为：
  - `invalid_feishu_webhook_url`
  - `feishu_delivered`
  - `feishu_status_code_xxx`
  - `feishu_code_xxx`
  - `feishu_malformed_response`
- 飞书 token 脱敏保护：
  - `endpoint_masked` 统一显示为 `https://open.feishu.cn/open-apis/bot/v2/hook/...`
  - `config_json` 不保存完整 URL，不保存 token
  - `notification_deliveries.payload_json` 不保存 webhook token
  - `response_body` / `message` 不保留 token
- `NotificationsPage` 通道类型增加“飞书”。
- `NotificationsPage` 飞书 webhook placeholder 使用 `https://open.feishu.cn/open-apis/bot/v2/hook/...`。
- `AlertsPage` 手动发送通知通道选择支持 `webhook / wecom / feishu`。

## 明确未做 / 禁止误解

- 未做飞书签名校验。
- 未支持 Lark 国际版。
- 未新增数据库表。
- 未新增 migration。
- 未新增自动通知入口。
- 未新增 channelId 为空群发入口。
- 未做通知重试队列。
- 未做告警升级。
- 未做通知编排。
- 未修改 alert lifecycle 语义。
- 未写 `alert_lifecycle_events`。
- 未修改 sync-once / scheduled sync / rule evaluation / alert generation 语义。
- 未绕过 `alerts` 直接从 `standard_events`、`alert_decisions`、raw payload、title、message 发送通知。
- 未引入 AI / XGBoost / Agent orchestration。
- 未引入 Kafka / Redis / ClickHouse。

## 当前关键边界

- Notification delivery 仍只能从既有 `alerts` 出发。
- 手动通知入口仍为 `POST /api/notifications/alerts/send`。
- 请求体仍只允许 `alertId + channelId`。
- alert 必须存在且 `status = open`。
- notification channel 必须 `enabled = true`。
- `channelId` 必填，不允许空 channel 群发。
- Notification code 不得修改 alert lifecycle 状态。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery service。
- legacy `/api/notifications/send` 和 `/api/notifications/channels/{id}/test` 仍返回 `use_alert_notification_endpoint`。
- 飞书 token 不得落入响应、错误信息、delivery payload、masked endpoint、config_json 或测试失败消息。

## 验证结果

- 后端 alert：`cd backend; mvn -pl edsp-alert -am test` 通过，40 tests，0 failures，BUILD SUCCESS。
- 后端 core：`cd backend; mvn -pl edsp-core -am test` 通过，112 tests，0 failures，BUILD SUCCESS。
- 前端：`cd frontend; npm.cmd run build` 通过，仅有既有 Vite chunk size warning。
- Git 检查：`git diff --check` 无 whitespace error；Git `autocrlf` line-ending warning 不阻塞。
- 代码审查：Feishu Notification Adapter MVP review 已完成；发现 1 个 P1，已修复并复测。

## 已知后续项

- 如需飞书签名校验，应单独规划新阶段，新增明确的 secret 存储、签名计算、脱敏和测试边界。
- 如需 Lark 国际版，应单独规划新阶段，不要混入中国飞书适配器。
- 如需自动通知、重试队列、失败重放、升级编排，应单独规划通知调度阶段。
- 后续新增邮件、短信等通知适配器时，仍需保持 `alertId + channelId` 手动发送边界，除非新阶段明确改变触发模型。

## 下一轮建议

下一阶段建议进入 `Notification Delivery Hardening MVP` 或继续单独增加下一个通知适配器。

建议范围：

- 保持手动通知入口和 `alertId + channelId` 边界。
- 对通知 delivery 的展示、失败原因筛选、脱敏校验做增强。
- 如继续扩展通道，每个适配器必须有独立 URL 校验、timeout、失败语义、脱敏和 mock HTTP 测试。
- 不做自动通知、不做重试队列、不做升级编排、不接入 AI / Kafka / Redis / ClickHouse。
