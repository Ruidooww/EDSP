# 数据安全预警分析平台 Handoff

更新时间：2026-05-26
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Secret Backfill Readiness MVP`
- 最新 feature merge commit：`f7d5ffe merge: notification secret backfill readiness mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification secret backfill readiness mvp`
- 本轮阶段分支：`codex/notification-secret-backfill-readiness-mvp`
- 本轮结果：已让 legacy plaintext 通知通道可筛选、可定位、可人工重配；未做自动 backfill / cleanup。

## 已完成能力

- 后端扩展现有 `GET /api/notifications/channels` 查询参数：
  - `secretStorageStatus=encrypted|legacy_plaintext|missing`
  - `enabled=true|false`
  - 两个 filter 可组合。
  - 不传参数时保持现有列表行为。
- 后端参数校验：
  - 非法 `secretStorageStatus` 返回 `400 invalid_secret_storage_status`。
  - 非法 `enabled` 返回 `400 invalid_enabled_filter`。
  - 未使用 `Boolean.parseBoolean(...)` 静默把非法值转成 `false`。
- 通道列表 response 继续保持 secret-safe：
  - 不返回 `endpoint_url`。
  - 不返回 `endpoint_secret_ciphertext`。
  - 不返回 `endpoint_secret_key_version`。
  - `endpoint_masked` 返回前继续走 sanitizer 兜底。
- 前端 `NotificationsPage` 增加通道筛选：
  - 密钥状态：全部 / 已加密 / 待重配 / 未配置。
  - 启用状态：全部 / 启用 / 停用。
  - 筛选通过后端 query 参数完成，不做本地过滤。
  - “全部”状态不发送对应 query 参数。
- 前端编辑提示：
  - 仅 legacy plaintext 通道编辑时提示：`重新输入 Webhook URL 后将转换为加密存储`。
  - encrypted 通道继续提示留空保留现有密钥。
  - missing 通道提示需要输入 endpoint 才能启用。
- 已保持上一轮 hardening 语义：
  - `legacy_plaintext + update 不传 endpoint` 保留 `endpoint_url` fallback。
  - `legacy_plaintext + update 传新 endpoint` 转换为 encrypted。
  - `encrypted + update 不传 endpoint` 保留原 encrypted secret。
  - `missing + no endpoint + finalEnabled=true` 继续拒绝 `notification_secret_unavailable`。

## 明确未做 / 禁止误解

- 未新增 migration。
- 未新增 API endpoint。
- 未做自动 backfill。
- 未做自动 cleanup。
- 未做历史数据批量清洗。
- 未做批量迁移。
- 未做批量清空。
- 未做批量禁用。
- 未做批量重配。
- 未读取、未改写、未脱敏重写历史 `notification_deliveries` 数据。
- 未修改历史 `notification_channels.endpoint_url` 数据。
- 未清空 legacy plaintext `endpoint_url`。
- 未修改 V16 字段定义。
- 未做 key rotation。
- 未接 Vault / KMS。
- 未接外部 secret provider。
- 未修改 notification send / retry API 语义。
- 未修改 alert lifecycle。
- 未写 `alert_lifecycle_events`。
- 未修改 rule evaluation。
- 未修改 alert generation。
- 未修改 sync-once / scheduled sync。
- 未混入 `Docker Compose Container Name Hardening MVP`。
- 未修改 Docker / compose。
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
- legacy plaintext 现在可通过 `secretStorageStatus=legacy_plaintext` 定位并人工重配，但不会自动迁移。

## 测试和验证结果

- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过，`86` tests。
  - `mvn -pl edsp-core -am test` 通过，`116` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `git diff --check` 通过；无 whitespace error，仅有 CRLF/LF warning。
  - `git status --short --branch` clean after branch commit / push。
- 本轮新增 / 覆盖测试：
  - `secretStorageStatus=encrypted` 查询。
  - `secretStorageStatus=legacy_plaintext` 查询。
  - `secretStorageStatus=missing` 查询。
  - `enabled=true / false` 查询。
  - `secretStorageStatus + enabled` 组合查询。
  - 非法 `secretStorageStatus` 返回 `invalid_secret_storage_status`。
  - 非法 `enabled` 返回 `invalid_enabled_filter`。
  - channels response 不返回 raw endpoint / ciphertext / key version。
  - legacy edit 传新 endpoint 后仍转换为 encrypted。
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮是 readiness，不是 cleanup；legacy plaintext 仍允许 fallback send / retry。
- 历史 `notification_channels.endpoint_url` 中已有 plaintext endpoint，本轮未做 backfill / cleanup。
- 历史 `notification_channels.config_json` 中如已有敏感值，本轮未做 migration 清理。
- 历史 `notification_deliveries.response_body / failure_reason / payload_json` 中如已有敏感值，本轮未做 migration 清理。
- 本轮未做 key rotation；如果 master key 丢失或更换，encrypted channel 仍需要后续恢复 / 轮换策略。
- 当前 `docker-compose.yml` 固定 `container_name` 会导致不同 compose project 无法并行启动同一套 EDSP 容器；如需处理，应单独规划 `Docker Compose Container Name Hardening MVP`，不要混入通知业务阶段。

## 下一轮建议

建议下一阶段再评估是否进入 `Notification Secret Backfill / Cleanup MVP`：

- 先设计 dry-run / audit / rollback 边界。
- 明确是否允许自动 backfill，或继续只支持人工重配。
- 明确失败恢复策略和审计记录。
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
