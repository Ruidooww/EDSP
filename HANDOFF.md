# 数据安全预警分析平台 Handoff

更新时间：2026-05-25
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Delivery Reliability MVP`
- 最新 feature merge commit：`4bf8f9c merge: notification delivery reliability mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification delivery reliability mvp`
- 本轮阶段分支：`codex/notification-delivery-reliability-mvp`
- 本轮结果：已完成通知投递可靠性增强，支持结构化失败诊断和基于失败 delivery 的手动 retry。

## 已完成能力

- 新增 migration：`V15__notification_delivery_reliability.sql`。
- `notification_deliveries` 新增字段：
  - `failure_type`
  - `failure_reason`
  - `retryable`
  - `retry_of_delivery_id`
  - `retry_count`
- 新增索引：
  - `idx_notification_deliveries_failure_type`
  - `idx_notification_deliveries_status_retryable`
  - `idx_notification_deliveries_retry_of`
- 通知发送结果写入结构化失败信息：
  - success delivery 保持 `failure_type = null`、`failure_reason = null`、`retryable = false`
  - failed delivery 写入固定 `failure_type`、脱敏后的 `failure_reason` 和固定规则计算出的 `retryable`
- 固定失败类型范围：
  - `timeout`
  - `connection_error`
  - `http_408`
  - `http_429`
  - `http_5xx`
  - `http_4xx`
  - `provider_business_error`
  - `malformed_response`
  - `invalid_endpoint`
  - `unsupported_channel`
  - `unknown_error`
- 固定 retryable 规则：
  - 可重试：`timeout`、`connection_error`、`http_408`、`http_429`、`http_5xx`
  - 不可重试：`http_4xx`、`provider_business_error`、`malformed_response`、`invalid_endpoint`、`unsupported_channel`、`unknown_error`
- 新增手动 retry API：
  - `POST /api/notifications/deliveries/{id}/retry`
- retry API 边界：
  - 不接受业务 body
  - 不允许传入 `alertId`、`channelId`、`title`、`message`、`severity`、`payload`
  - 只能读取原 delivery 的 `alert_id + channel_id`
  - 重新走现有 alert-based sending path
  - 不复用旧 `payload_json` 直接发送
- retry 允许条件：
  - 原 delivery 必须存在
  - `status = failed`
  - `retryable = true`
  - 原 delivery 必须有关联 `alert_id` 和 `channel_id`
  - alert 必须存在且 `status = open`
  - channel 必须存在且 enabled
  - channel type 必须被现有 adapter registry 支持
- retry 写入规则：
  - 实际 retry 后一定新增一条 `notification_deliveries`
  - 新 delivery 写入 `retry_of_delivery_id`
  - 新 delivery 的 `retry_count = 0`
  - 原 delivery 只递增 `retry_count`
  - 原 delivery 不修改 `status`、`response_body`、`payload_json`、`failure_type`、`failure_reason`、`retryable`、`retry_of_delivery_id`
- retry 服务加 `@Transactional`，保证新增 retry delivery 和原 delivery retry count 更新在同一事务内。
- `GET /api/notifications/deliveries` 返回新增 reliability 字段。
- 前端 `NotificationsPage` 投递记录区域展示：
  - 失败类型
  - 失败原因
  - 是否可重试
  - 重试次数
  - `Retry Of`
- 前端仅对 `status = failed && retryable = true` 的投递记录显示“重试一次”按钮。

## 明确未做 / 禁止误解

- 未做自动重试。
- 未做定时重试。
- 未做失败队列。
- 未做后台 retry worker。
- 未做批量 retry。
- 未做通知升级。
- 未做通知编排。
- 未新增通知通道 adapter。
- 未接外部数据库。
- 未接旧预警平台库。
- 未做外部 schema discovery。
- 未修改 alert lifecycle。
- 未写入 `alert_lifecycle_events`。
- 未修改 alert status。
- 未修改 `alerts / alert_decisions / standard_events` 语义。
- 未修改 `POST /api/notifications/alerts/send` 的 `alertId + channelId` 边界。
- 未修改 `AGENTS.md`。
- 未引入 Kafka / Redis / ClickHouse / AI。

## 当前关键边界

- Notification delivery 仍只能从已有 `alerts` 出发。
- 正常通知发送入口仍是 `POST /api/notifications/alerts/send`。
- 正常通知发送请求体仍只允许 `alertId + channelId`。
- retry 只能从原 delivery 的 `alert_id + channel_id` 重新进入 alert-based sending path。
- retry 不得复用旧 `payload_json` 直接发送。
- retry 校验失败不得写新 delivery，不得递增原 delivery 的 `retry_count`，不得修改原 delivery。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。
- Rule evaluation 不得直接创建 alerts 或发送 notifications。
- Alert generation 只能从 matched `alert_decisions` 创建 alerts。

## 测试结果

- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过，`Tests run: 54, Failures: 0, Errors: 0`
  - `mvn -pl edsp-core -am test` 通过，`Tests run: 112, Failures: 0, Errors: 0`
  - `npm.cmd run build` 通过，仅有 Vite chunk size warning
  - `git diff --check` 通过，仅有 CRLF warning，无 whitespace error
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- GitHub Actions branch run 需要在远端完成后观察结果；本地未安装 `gh`，无法直接查询 Actions run。
- 当前 `failure_type` 由服务层固定写入，数据库层暂未加 check constraint；如后续需要更强约束，可单独规划 migration hardening。
- 当前 retry 仅支持手动单条 retry；自动 retry、批量 retry 和升级编排仍需保持禁用，除非后续单独立项。
- 前端投递记录列宽已扩展；如后续数据较多，可单独做投递记录详情页或筛选增强。

## 下一轮建议

建议下一阶段先不要扩展自动化通知，优先做 `Notification Delivery Observability MVP` 或回到业务主线做告警处置视图增强。

可选方向：

- `Notification Delivery Observability MVP`
  - 增加投递记录筛选体验
  - 增加失败类型统计
  - 增加按 alert / channel 的投递历史查看
  - 不做自动 retry，不做升级编排
- 告警处置视图增强
  - 提升 alerts 列表和详情页可读性
  - 展示关联通知投递记录
  - 仍保持通知只手动触发

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
