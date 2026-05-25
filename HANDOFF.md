# 数据安全预警分析平台 Handoff

更新时间：2026-05-25
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Secret Hygiene MVP`
- 最新 feature merge commit：`290fdd7 merge: notification secret hygiene mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification secret hygiene mvp`
- 本轮阶段分支：`codex/notification-secret-hygiene-mvp`
- 本轮结果：已完成通知密钥脱敏加固；不新增 migration，不改变 `notification_channels.endpoint_url` 存储方式，不改变通知发送 / retry 语义。

## 已完成能力

- 新增 `NotificationSecretSanitizer`，统一处理：
  - endpoint masking
  - response body redaction
  - error / failure reason redaction
  - config_json sanitization
  - endpoint-derived secret redaction
- `NotificationService` 加固：
  - `GET /api/notifications/channels` 继续只返回 `endpoint_masked`
  - `GET /api/notifications/deliveries` 返回前脱敏 `response_body` / `failure_reason` / `payload_json`
  - create / update channel 时清理 `config_json` 中的 URL / key / token / secret / auth 等敏感字段和值
- `WebhookClient` 加固：
  - HTTP response body、provider error、exception message 写入前脱敏
  - 支持普通 webhook query secret、URL userinfo、token-like path segment、Bearer / Authorization / access_token / signature 等模式脱敏
- `WeComNotificationAdapter` / `FeishuNotificationAdapter` 加固：
  - provider business error / malformed response / response body / message 写入前脱敏
  - Feishu hook token 固定提取脱敏，不受普通 path token 长度阈值影响
- `AlertNotificationService` 加固：
  - 写入 `notification_deliveries.response_body`
  - 写入 `notification_deliveries.failure_reason`
  - 返回 send result / retry result
  - 保存 `payload_json` 前只对当前通道 endpoint 派生出的敏感 secret 做最小脱敏，避免破坏普通业务告警 detail
- `OverviewController` 加固：
  - `GET /api/core/overview` 的 recent failed delivery 不返回 `payload_json` / `response_body`
  - `failure_reason` 返回前脱敏 endpoint-derived 裸 secret、Feishu token、Bearer、显式 token / secret assignment
- 补充后端测试覆盖：
  - endpoint_masked 不泄露 generic webhook / WeCom / Feishu secret
  - config_json 新建 / 更新不保存敏感字段和值
  - send / retry / delivery list / overview 不泄露通道 secret
  - payload_json 不包含当前通道 endpoint URL / webhook token / WeCom key / Feishu token
  - 不触发真实公网 HTTP 请求

## 明确未做 / 禁止误解

- 未新增 migration。
- 未修改 `notification_channels` 表结构。
- 未修改 `notification_channels.endpoint_url` 存储方式。
- 未做历史数据清洗 migration。
- 未引入 Vault / KMS。
- 未做 encryption-at-rest。
- 未引入加密库。
- 未新增通知通道。
- 未做 Email / SMS Adapter。
- 未做飞书签名校验。
- 未做 Lark 国际版。
- 未做自动通知。
- 未做自动 retry / 批量 retry / 失败重放。
- 未做通知升级 / 编排。
- 未修改 alert lifecycle。
- 未写 `alert_lifecycle_events`。
- 未修改 alerts / alert_decisions / standard_events 语义。
- 未修改 rule evaluation / alert generation 语义。
- 未修改 sync-once / scheduled sync 语义。
- 未修改 `AGENTS.md`。
- 未处理 PostgreSQL Runtime Verification MVP。
- 未引入 Kafka / Redis / ClickHouse / AI。

## 当前关键边界

- `notification_channels.endpoint_url` 仍作为内部发送所需字段保存在数据库中；本轮只处理 API response、delivery 记录、失败原因、payload preview、前端展示、测试断言等“不外泄”路径。
- `GET /api/notifications/channels` 不得返回 `endpoint_url`，只返回脱敏后的 `endpoint_masked`。
- `notification_deliveries.payload_json` 是 alert-based payload，不得新增 channel endpoint / webhookUrl / endpointUrl / token / key / secret 等通道密钥字段。
- 对 `payload_json` 的脱敏应保持克制，只清理当前 channel endpoint 派生出的敏感 secret，不应破坏普通业务告警 detail。
- 通知发送入口仍只能是 `POST /api/notifications/alerts/send`，请求边界仍是 `alertId + channelId`。
- retry 入口仍只能是 `POST /api/notifications/deliveries/{id}/retry`。
- Notification delivery 只能从 existing `alerts` 出发，不能直接从 `standard_events` / `alert_decisions` / raw payload 发送。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。
- Rule evaluation 不得直接创建 alerts 或发送 notifications。
- Alert generation 只能从 matched `alert_decisions` 创建 alerts。

## 测试结果

- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过：`Tests run: 60, Failures: 0, Errors: 0, Skipped: 0`
  - `mvn -pl edsp-core -am test` 通过：`Tests run: 115, Failures: 0, Errors: 0, Skipped: 0`
  - `npm.cmd run build` 通过，仅有 Vite chunk size warning
  - `git diff --check` 通过，无 whitespace error
- 敏感关键词扫描：
  - 生产代码只命中 redaction 正则和脱敏逻辑本身
  - 测试代码命中测试用 secret 常量和断言，符合预期
  - 前端未发现 raw endpoint / reveal / copy secret 入口
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 历史 `notification_channels.endpoint_url` 中已存在的完整 URL 或 token，本轮未做 migration 清理。
- 历史 `notification_channels.config_json` 中已存在的敏感值，本轮未做 migration 清理。
- 历史 `notification_deliveries.response_body / failure_reason / payload_json` 中已存在的敏感值，本轮未做 migration 清理。
- 本轮不解决数据库静态存储完整 webhook URL / token 的问题；后续如要做到数据库层不存明文 secret，需要单独规划 secret storage / encryption-at-rest / 外部 secret provider。
- `OverviewController` 当前在 `edsp-core` 内保留最小本地 redaction 逻辑；如后续出现更多跨模块只读展示脱敏需求，可以规划 shared sanitizer，但不要为了本轮过度抽象。

## 下一轮建议

建议下一阶段优先做 `Notification Secret Storage MVP`，目标是解决当前已知的数据库静态 secret 存储问题。

建议范围：

- 设计 `notification_channels.endpoint_url` 明文存储替代方案。
- 明确是否采用应用层加密、外部 secret provider，或最小本地 secret reference。
- 规划兼容旧数据的 migration / fallback 策略。
- 保持通知发送入口、retry 入口、alert lifecycle、rule evaluation、alert generation 语义不变。
- 不引入自动通知、通知升级、复杂编排、AI、Kafka、Redis、ClickHouse。

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
