# 数据安全预警分析平台 Handoff

更新时间：2026-05-26
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Secret Storage Runtime Verification MVP`
- 最新 feature merge commit：`78dd807 merge: notification secret storage runtime verification mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification secret storage runtime verification mvp`
- 本轮阶段分支：`codex/notification-secret-storage-runtime-verification-mvp`
- 本轮结果：已在真实 Docker PostgreSQL runtime 下验证 Notification Secret Storage Foundation，确认 V16 migration、secret storage 字段、核心服务启动、gateway/frontend 代理和只读 API smoke test 可用；同时修复 `NotificationSecretStore` Spring 注入导致 `edsp-alert` runtime 启动失败的问题。

## 已完成能力

- 新增 runtime verification 文档：
  - `docs/notification-secret-storage-runtime-verification.md`
  - 记录 Docker Compose project、服务启动状态、PostgreSQL readiness、Flyway V16、V16 字段、HTTP smoke test、测试结果、敏感信息检查和 known risks。
- 真实 PostgreSQL runtime 验证：
  - 使用既有非破坏性 Docker Compose project：`edsp-pg-verify`
  - 未执行 `docker compose down -v`
  - 未删除任何 volume
  - 未手动改库绕过 Flyway
  - PostgreSQL 容器保持 healthy
- Flyway 验证：
  - `flyway_schema_history` 中 V16 `notification secret storage` 存在且 `success = true`
  - 同时确认 V15 / V14 仍成功
  - 未发现 checksum / duplicate migration / SQL compatibility 错误
- V16 字段验证：
  - `notification_channels.endpoint_secret_ciphertext`
  - `notification_channels.endpoint_secret_key_version`
  - `notification_channels.endpoint_masked`
  - `notification_channels.secret_storage_status`
  - `secret_storage_status` 状态分布查询可正常执行
- Runtime blocker 修复：
  - 修复 `NotificationSecretStore` 无默认构造函数导致 Spring runtime bean 创建失败的问题
  - 为生产构造函数增加 Spring 注入标记
  - 新增 `NotificationSecretStoreSpringContextTest` 覆盖 Spring bean 创建
- HTTP smoke test 验证：
  - `GET /api/notifications/channels` 返回 `200 OK`
  - `GET /api/notifications/deliveries?limit=10` 返回 `200 OK`
  - `GET /api/core/overview` 返回 `200 OK`
  - API response 未包含 `endpoint_secret_ciphertext`
  - API response 未包含 `endpoint_secret_key_version`
  - API response 未包含 raw `endpoint_url`
  - API response 未包含 webhook token / WeCom key / Feishu token

## 明确未做 / 禁止误解

- 未做新业务功能。
- 未做 Notification Secret Storage Hardening。
- 未做 partial update。
- 未做“不传 webhookUrl 保留旧 secret”。
- 未做历史数据批量清洗。
- 未做 backfill / cleanup。
- 未做 key rotation。
- 未接 Vault / KMS。
- 未接外部 secret provider。
- 未接外部业务库。
- 未新增 Email / SMS adapter。
- 未新增通知通道 adapter。
- 未修改 notification send / retry API 语义。
- 未修改 alert lifecycle。
- 未写 `alert_lifecycle_events`。
- 未修改 rule evaluation。
- 未修改 alert generation。
- 未修改 sync-once / scheduled sync 语义。
- 未修改 `AGENTS.md`。
- 未修改旧文档 `docs/postgresql-runtime-verification.md`。
- 未执行 `docker compose -p edsp-pg-verify down -v`。
- 未删除 `edsp-pg-verify_postgres_data` 或 `edsp_postgres_data` volume。

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
- runtime 验证阶段只允许非破坏性 Docker / PostgreSQL 检查，不允许删除 volume 或手动改库绕过 Flyway。

## 测试和验证结果

- Runtime verification：
  - Docker Compose project：`edsp-pg-verify`
  - PostgreSQL healthcheck：healthy
  - V16 migration：`success = true`
  - V16 fields：全部存在
  - HTTP smoke test：`/api/notifications/channels`、`/api/notifications/deliveries?limit=10`、`/api/core/overview` 均返回 `200 OK`
  - 敏感信息检查：API response 未命中 secret storage ciphertext / key version / raw endpoint / webhook token / WeCom key / Feishu token
- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过：`Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`
  - `mvn -pl edsp-core -am test` 通过：`Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`
  - `npm.cmd run build` 通过，仅有既有 Vite chunk size warning
  - `git diff --check` 通过，无 whitespace error
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 现有 `edsp-pg-verify` runtime 使用的数据库是 `edsp_pg_verify`，而 compose 默认数据库名仍可能是 `edsp`；后续如需稳定复现，应明确 runtime env。
- 当前 `docker-compose.yml` 使用固定 `container_name`，不同 compose project 无法并行启动同一套 EDSP 容器；如需多 project 并行验证，应单独规划 `Docker Compose Container Name Hardening MVP`。
- 历史 `notification_channels.endpoint_url` 中已存在的完整 URL 或 token，本轮未做 migration 清理。
- 历史 `notification_channels.config_json` 中已存在的敏感值，本轮未做 migration 清理。
- 历史 `notification_deliveries.response_body / failure_reason / payload_json` 中已存在的敏感值，本轮未做 migration 清理。
- 本轮 runtime verification 不代表真实外部 webhook / WeCom / Feishu 网络可达。
- 测试 key 只能用于验证，不得用于生产。

## 下一轮建议

建议下一阶段优先做 `Notification Secret Storage Hardening MVP`：

- 规划 partial update：未传 endpoint 时保留旧 encrypted secret。
- 规划历史 legacy plaintext channel 的可控迁移 / 人工重配策略。
- 继续保持 send / retry / alert lifecycle / rule evaluation / alert generation 语义不变。
- 不做 Vault / KMS，除非单独立项。
- 不做外部 secret provider，除非单独立项。

可作为后续独立阶段的工程项：

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
