# 数据安全预警分析平台 Handoff

更新时间：2026-05-26
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Secret Storage Hardening MVP`
- 最新 feature merge commit：`38923cd merge: notification secret storage hardening mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification secret storage hardening mvp`
- 本轮阶段分支：`codex/notification-secret-storage-hardening-mvp`
- 本轮结果：已完成通知通道 partial update hardening。编辑通知通道时可只修改名称、描述、启用状态和配置；未传 endpoint 时保留既有 encrypted secret 或 legacy plaintext fallback；传入新 endpoint 时才重新校验并重新加密。

## 已完成能力

- 后端 `PUT /api/notifications/channels/{id}` 支持 partial update：
  - 未传 `webhookUrl` / `endpointUrl` 时保留旧 secret。
  - blank / whitespace endpoint 返回 `400`，不清空旧 secret。
  - 传入新 endpoint 时重新校验、重新加密，并保持 `endpoint_url = null`。
  - 未传 `enabled` / `description` / `config` / `name` 时按规则保留旧值。
  - 显式 `enabled=false` 可禁用通道。
  - 显式 `description=null` 可清空描述。
  - `config={}` 可更新为空配置，并继续经过 sanitizer。
- encrypted channel update：
  - 不传 endpoint 时保留 `endpoint_secret_ciphertext`、`endpoint_secret_key_version`、`endpoint_masked`、`secret_storage_status=encrypted`。
  - 不传 endpoint 时不调用 `NotificationSecretStore.storeEndpoint(...)`，不要求 master key。
  - 传新 endpoint 时重新加密，旧 token 不进入 DB / API response。
- legacy plaintext channel update：
  - 不传 endpoint 时保留 `endpoint_url` fallback，不生成 ciphertext，不要求 master key。
  - 传新 endpoint 时转换为 encrypted，并清空 `endpoint_url`。
- missing channel update：
  - `missing + no endpoint + finalEnabled=true` 返回 `notification_secret_unavailable`。
  - `missing + no endpoint + finalEnabled=false` 允许更新 metadata 并保持 missing。
  - `missing + new endpoint` 转换为 encrypted。
- channel type hardening：
  - update 未传 `channelType` 时保留旧值。
  - update 传相同 `channelType` 允许。
  - update 传不同 `channelType` 返回 `channel_type_immutable`。
- 前端 `NotificationsPage`：
  - 支持编辑通知通道，不再要求重新输入 endpoint。
  - endpoint 留空表示保留现有密钥。
  - 空格 endpoint 会被识别为无效输入。
  - 新增只读密钥状态展示：`encrypted` = 已加密，`legacy_plaintext` = 待重配，`missing` = 未配置。
  - 不展示 raw endpoint / ciphertext / key version。

## 明确未做 / 禁止误解

- 未新增 migration。
- 未修改 V16 字段定义。
- 未删除 `endpoint_url` 字段。
- 未做历史 secret backfill / cleanup。
- 未做历史数据批量清洗。
- 未做 key rotation。
- 未接 Vault / KMS。
- 未接外部 secret provider。
- 未新增 Email / SMS adapter。
- 未新增通知通道 adapter。
- 未做自动通知。
- 未做自动 retry / 批量 retry / 失败队列。
- 未做通知升级 / 通知编排。
- 未修改 notification send / retry API 语义。
- 未修改 alert lifecycle。
- 未写 `alert_lifecycle_events`。
- 未修改 rule evaluation。
- 未修改 alert generation。
- 未修改 sync-once / scheduled sync 语义。
- 未混入 Docker Compose Container Name Hardening。
- 未修改 `AGENTS.md`。

## 当前关键边界

- 通知发送入口仍只能是 `POST /api/notifications/alerts/send`，请求边界仍是 `alertId + channelId`。
- retry 入口仍只能是 `POST /api/notifications/deliveries/{id}/retry`。
- Notification delivery 只能从 existing `alerts` 出发，不能直接从 `standard_events` / `alert_decisions` / raw payload 发送。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。
- Rule evaluation 不得直接创建 alerts 或发送 notifications。
- Alert generation 只能从 matched `alert_decisions` 创建 alerts。
- `GET /api/notifications/channels` 不得返回 `endpoint_url` / `endpoint_secret_ciphertext` / `endpoint_secret_key_version`。
- `endpoint_masked` 返回前必须继续经过 sanitizer 兜底。
- `notification_deliveries.payload_json` 不得新增 channel endpoint / webhookUrl / endpointUrl / token / key / secret 等通道密钥字段。
- `GET /api/core/overview` 不得暴露 endpoint secret。
- legacy plaintext fallback 仍仅为兼容旧数据，不是新建 / 更新通道的默认存储路径。

## 测试和验证结果

- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过：`Tests run: 82, Failures: 0, Errors: 0, Skipped: 0`
  - `mvn -pl edsp-core -am test` 通过：`Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning
  - `git diff --check` 通过；无 whitespace error
- 敏感信息检查：
  - 前端未引用 `endpoint_url` / `endpoint_secret_ciphertext` / `endpoint_secret_key_version`
  - 生产代码 / 前端 diff 未发现测试 secret 常量泄露
  - 测试文件中的 `WEBHOOKTOKEN` 等仅用于断言覆盖
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 历史 `notification_channels.endpoint_url` 中已有 plaintext endpoint，本轮未做 backfill / cleanup。
- 历史 `notification_channels.config_json` 中如已有敏感值，本轮未做 migration 清理。
- 历史 `notification_deliveries.response_body / failure_reason / payload_json` 中如已有敏感值，本轮未做 migration 清理。
- `legacy_plaintext` 仍保留 fallback，只在前端显示为“待重配”。
- 本轮未做 key rotation；如果 master key 丢失或更换，encrypted channel 仍需要后续恢复 / 轮换策略。
- 当前 `docker-compose.yml` 固定 `container_name` 会导致不同 compose project 无法并行启动同一套 EDSP 容器；如需处理，应单独规划 `Docker Compose Container Name Hardening MVP`，不要混入通知业务阶段。

## 下一轮建议

建议下一阶段优先做 `Notification Secret Backfill / Cleanup MVP`：

- 规划 legacy plaintext channel 的可控迁移 / 人工重配策略。
- 明确是否允许后台 backfill，或仅提供只读排查与手动重配入口。
- 保持 send / retry / alert lifecycle / rule evaluation / alert generation 语义不变。
- 不接 Vault / KMS，除非单独立项。
- 不做 key rotation，除非单独立项。

可作为独立工程阶段排期：

- `Docker Compose Container Name Hardening MVP`
  - 解决固定 `container_name` 导致不同 compose project 无法并行启动的问题。
  - 明确 `POSTGRES_DB` / runtime project name / volume 使用规则。
  - 不混入通知业务语义修改。

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
