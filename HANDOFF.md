# 数据安全预警分析平台 Handoff

更新时间：2026-05-25
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Secret Storage Foundation MVP`
- 最新 feature merge commit：`6e46078 merge: notification secret storage foundation mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification secret storage foundation mvp`
- 本轮阶段分支：`codex/notification-secret-storage-foundation-mvp`
- 本轮结果：已完成通知通道密钥加密存储基础能力；保留 legacy plaintext fallback，不删除 `endpoint_url` 字段，不做历史数据批量清洗，不改变 send / retry / alert lifecycle 语义。

## 已完成能力

- 新增 V16 migration：`V16__notification_secret_storage.sql`
  - `notification_channels.endpoint_secret_ciphertext`
  - `notification_channels.endpoint_secret_key_version`
  - `notification_channels.endpoint_masked`
  - `notification_channels.secret_storage_status`
  - `secret_storage_status` 约束：`encrypted` / `legacy_plaintext` / `missing`
  - `secret_storage_status` 索引
- 新增 `NotificationSecretCodec`
  - 使用 `AES/GCM/NoPadding`
  - 使用 12-byte `SecureRandom` nonce
  - ciphertext 格式：`v1:<base64-nonce>:<base64-ciphertext-with-tag>`
  - master key 要求为 Base64 编码的 32 bytes
- 新增 `NotificationSecretStore`
  - master key 读取顺序：
    - `edsp.notification.secret.master-key`
    - `EDSP_NOTIFICATION_SECRET_KEY`
  - 明确区分 key missing / key invalid / decrypt failed 语义
  - 支持 encrypted channel 解密发送
  - 支持 legacy plaintext channel fallback 使用 `endpoint_url`
  - missing secret 返回 `notification_secret_unavailable`
- `NotificationService` 加固
  - 新建 / 更新通道后 `endpoint_url = null`
  - `endpoint_secret_ciphertext` 保存加密结果，不保存 raw endpoint / token / key
  - `endpoint_masked` 不泄露 webhook token / WeCom key / Feishu token
  - `config_json` 不保存 `webhookUrl` / `endpointUrl` / `url` / `key` / `token` / `secret` / `authorization` / `bearer`
  - `GET /api/notifications/channels` 不返回 `endpoint_url` / `endpoint_secret_ciphertext` / `endpoint_secret_key_version`
- `AlertNotificationService` 加固
  - send / retry 发送路径统一通过 `NotificationSecretStore.resolveEndpoint(channel)`
  - resolve 失败时不调用 adapter
  - resolve 失败时不写 `notification_deliveries`
  - retry resolve 失败时不递增 `retry_count`
  - 不修改 alert status
  - 不写 `alert_lifecycle_events`
- `NotificationSecretSanitizer` 加固
  - generic webhook `endpoint_masked` 统一收敛为 `scheme://host/...`
  - Feishu endpoint 展示为 `https://open.feishu.cn/open-apis/bot/v2/hook/...`
  - WeCom endpoint 展示为 `https://qyapi.weixin.qq.com/...`
  - response / failure / payload 脱敏逻辑继续保留上一轮 Secret Hygiene 边界
- `DemoDataSeeder` 调整
  - 不 seed WeCom / Feishu secret-like endpoint
  - demo channel 使用 `endpoint_url = null`
  - `endpoint_masked = demo://not-configured`
  - `secret_storage_status = missing`
  - `enabled = false`
  - `status = disabled`
  - 更新已有 demo channel 时清空历史 `endpoint_secret_ciphertext` / `endpoint_secret_key_version`
  - 未引入 `edsp-core -> edsp-alert` 依赖
- 补充测试覆盖
  - AES-GCM codec / key missing / invalid key / decrypt failed
  - create / update channel 加密存储和响应脱敏
  - encrypted send / retry
  - legacy plaintext fallback
  - missing secret 不调用 adapter、不写 delivery、不递增 retry_count
  - DemoDataSeeder 不写真实通知密钥
  - ciphertext 不包含原始 endpoint / token / key

## 明确未做 / 禁止误解

- 未做历史数据批量清洗。
- 未删除 `notification_channels.endpoint_url` 字段。
- 未接 Vault / KMS。
- 未新增 Email / SMS adapter。
- 未做飞书签名校验。
- 未做 Lark 国际版。
- 未做自动通知。
- 未做自动 retry / 批量 retry / 失败队列。
- 未做通知升级 / 编排。
- 未修改 alert lifecycle。
- 未写 `alert_lifecycle_events`。
- 未修改 send / retry API 请求语义。
- 未修改 alerts / alert_decisions / standard_events 语义。
- 未修改 rule evaluation / alert generation 语义。
- 未修改 sync-once / scheduled sync 语义。
- 未修改 `AGENTS.md`。
- 未处理 PostgreSQL Runtime Verification MVP。
- 未引入 Kafka / Redis / ClickHouse / AI。
- 未做 partial update 语义；本阶段不支持“不传 webhookUrl 保留旧 secret”。

## 当前关键边界

- 通知发送入口仍只能是 `POST /api/notifications/alerts/send`，请求边界仍是 `alertId + channelId`。
- retry 入口仍只能是 `POST /api/notifications/deliveries/{id}/retry`。
- Notification delivery 只能从 existing `alerts` 出发，不能直接从 `standard_events` / `alert_decisions` / raw payload 发送。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。
- Rule evaluation 不得直接创建 alerts 或发送 notifications。
- Alert generation 只能从 matched `alert_decisions` 创建 alerts。
- `GET /api/notifications/channels` 不得返回 `endpoint_url` / `endpoint_secret_ciphertext` / `endpoint_secret_key_version`。
- `notification_deliveries.payload_json` 不得新增 channel endpoint / webhookUrl / endpointUrl / token / key / secret 等通道密钥字段。
- `GET /api/core/overview` 不得暴露 endpoint secret。
- legacy plaintext fallback 仅为兼容旧数据，不是新建 / 更新通道的存储路径。

## 测试结果

- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过：`Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`
  - `mvn -pl edsp-core -am test` 通过：`Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`
  - `npm.cmd run build` 通过，仅有 Vite chunk size warning
  - `git diff --check` 通过，无 whitespace error
- 敏感关键词扫描：
  - 生产代码只命中结构字段名、脱敏正则和脱敏逻辑本身
  - `DemoDataSeeder` 只命中字段名，不包含真实 webhook / WeCom / Feishu endpoint
  - 测试代码命中测试用 secret 常量和断言，符合预期
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 历史 `notification_channels.endpoint_url` 中已存在的完整 URL 或 token，本轮未做 migration 清理。
- 历史 `notification_channels.config_json` 中已存在的敏感值，本轮未做 migration 清理。
- 历史 `notification_deliveries.response_body / failure_reason / payload_json` 中已存在的敏感值，本轮未做 migration 清理。
- 当前 master 已具备“新建 / 更新通道不再明文落 endpoint”的基础能力，但历史密钥治理仍需单独阶段。
- 如果后续需要“不传 webhookUrl 保留旧 secret”的编辑体验，应单独规划 partial update 语义和测试。

## 下一轮建议

建议下一阶段优先做 `Notification Secret Storage Hardening MVP` 或 `PostgreSQL Runtime Verification MVP`，二选一：

- `Notification Secret Storage Hardening MVP`
  - 规划 partial update：未传 endpoint 时保留旧 encrypted secret。
  - 规划历史 legacy plaintext channel 的可控迁移 / 人工重配策略。
  - 继续保持 send / retry / alert lifecycle / rule evaluation / alert generation 语义不变。
- `PostgreSQL Runtime Verification MVP`
  - 在真实 PostgreSQL / Docker runtime 下验证 V16 migration 和应用启动。
  - 项目目录为中文时，执行 Docker Compose 必须使用 `docker compose -p edsp ...`。
  - 不执行 `docker compose down -v`，不删除已有 volume。

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
