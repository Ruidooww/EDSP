# 数据安全预警分析平台 Handoff

更新时间：2026-05-26
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Secret Backfill Dry Run MVP`
- 最新 feature merge commit：`398d5fc merge: notification secret backfill dry run mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification secret backfill dry run mvp`
- 本轮阶段分支：`codex/notification-secret-backfill-dry-run-mvp`
- 本轮结果：已新增只读 dry-run 报告能力，用于审计 legacy plaintext 通知通道的理论迁移资格；未做真实 backfill / cleanup。

## 已完成能力

- 后端新增只读 API：`GET /api/notifications/secret-backfill/dry-run`。
  - 扫描 `notification_channels` 并生成 dry-run summary。
  - 返回 dry-run items 明细。
  - 不执行 insert / update / delete。
  - 不调用 `NotificationSecretStore.storeEndpoint(...)`。
  - 不调用 `NotificationSecretStore.resolveEndpoint(...)`。
  - 不要求 `edsp.notification.secret.master-key` 或 `EDSP_NOTIFICATION_SECRET_KEY`。
- dry-run 支持 query filters：
  - `enabled=true|false`。
  - `channelType=webhook|wecom|feishu`。
  - `limit=1..500`，默认 `100`。
- 后端参数校验：
  - 非法 `enabled` 返回 `400 invalid_enabled_filter`。
  - 非法 `channelType` filter 返回 `400 unsupported_channel`。
  - 非法 `limit` 返回 `400 invalid_limit`。
  - 未使用 `Boolean.parseBoolean(...)` 静默把非法值转成 `false`。
- dry-run 分类：
  - `encrypted` -> `dryRunStatus=already_encrypted`。
  - `missing` -> `dryRunStatus=missing`。
  - `legacy_plaintext + valid endpoint` -> `dryRunStatus=migration_eligible`。
  - `legacy_plaintext + missing endpoint` -> `blocked / endpoint_missing`。
  - `legacy_plaintext + invalid endpoint` -> `blocked / endpoint_invalid`。
  - `legacy_plaintext + unsupported channel_type` -> `blocked / unsupported_channel_type`。
- summary 统计口径固定：
  - `totalChannels` 为当前 filter 后总通道数。
  - `legacyPlaintext` 为当前 filter 后 legacy plaintext 总数。
  - `migrationEligible` 为 legacy plaintext 中理论可迁移数量。
  - `blocked` 为 legacy plaintext 中不可迁移数量。
  - `encrypted` / `missing` 单独统计，不混入 blocked。
  - items 可按 limit 截断，summary 不因 limit 截断失真。
  - 明细排序固定为 `updated_at desc, id desc`。
- response 继续保持 secret-safe：
  - 不返回 `endpoint_url`。
  - 不返回 `endpoint_secret_ciphertext`。
  - 不返回 `endpoint_secret_key_version`。
  - 不返回 raw token / key / secret。
  - endpoint 只通过 `endpointMasked` 返回，并继续经过 sanitizer / mask 兜底。
- 前端 `NotificationsPage` 新增只读 `密钥回填 Dry Run` 面板：
  - 展示 legacy 总数、理论可迁移数量、阻塞数量、已加密数量、missing 数量。
  - 展示阻塞原因分布。
  - 展示 `truncated` 状态。
  - 展示 dry-run 明细。
  - 支持按 `enabled` / `channelType` / `limit` 通过后端 query 参数刷新。
  - “查看 legacy 通道”仅联动现有通道筛选，不执行迁移。
- 前端未新增任何执行类入口：
  - 无一键迁移。
  - 无执行 backfill。
  - 无 cleanup。
  - 无批量清空 / 批量禁用 / 批量重配 / 批量迁移。
  - 无 reveal / copy raw endpoint。

## 明确未做 / 禁止误解

- 未新增 migration。
- 未做真实 backfill。
- 未做 cleanup。
- 未清空 legacy plaintext `endpoint_url`。
- 未修改历史 `notification_channels.endpoint_url` 数据。
- 未读取、未改写、未脱敏重写历史 `notification_deliveries` 数据。
- 未生成 `endpoint_secret_ciphertext`。
- 未修改 `endpoint_secret_key_version`。
- 未修改 `secret_storage_status`。
- 未修改 `updated_at`。
- 未调用 `NotificationSecretStore.storeEndpoint(...)`。
- 未调用 `NotificationSecretStore.resolveEndpoint(...)`。
- 未要求 `EDSP_NOTIFICATION_SECRET_KEY`。
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
- 未做 PostgreSQL Runtime Verification。
- 未做 Notification Secret Storage Runtime Verification。
- 未新增或修改 `docs/notification-secret-storage-runtime-verification.md`。
- 未修改 Docker / compose。
- 未修改 `AGENTS.md`。

## 当前关键边界

- dry-run API 是审计报告接口，只能只读扫描 `notification_channels`。
- dry-run 中 filter 参数非法时整体返回 400；DB 中单条历史脏数据不得导致整个 dry-run 失败，只能在 item 上标记 blocked。
- dry-run response 不得返回 raw endpoint / ciphertext / key version / raw token / key / secret。
- dry-run 不得要求 master key，不得调用 secret store，不得生成 ciphertext。
- 通知发送入口仍只能是 `POST /api/notifications/alerts/send`，请求边界仍是 `alertId + channelId`。
- retry 入口仍只能是 `POST /api/notifications/deliveries/{id}/retry`。
- Notification delivery 只能从 existing `alerts` 出发，不能直接从 `standard_events` / `alert_decisions` / raw payload 发送。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。
- Rule evaluation 不得直接创建 alerts 或发送 notifications。
- Alert generation 只能从 matched `alert_decisions` 创建 alerts。
- `GET /api/notifications/channels` 不得返回 `endpoint_url` / `endpoint_secret_ciphertext` / `endpoint_secret_key_version`。
- `endpoint_masked` 返回前必须继续经过 sanitizer 兜底。
- legacy plaintext fallback 仍仅为兼容旧数据，不是新建 / 更新通道的默认存储路径。

## 测试和验证结果

- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过，`92` tests。
  - `mvn -pl edsp-core -am test` 通过，`116` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `git diff --check` 通过；无 whitespace error，仅有 CRLF/LF warning。
  - `git status --short --branch` clean after branch commit / push。
- 本轮新增 / 覆盖测试：
  - dry-run 基本 summary 统计。
  - encrypted / missing / legacy valid / legacy missing endpoint / legacy invalid endpoint / unsupported channel type 分类。
  - 单条历史脏数据不导致整体失败。
  - 非法 `enabled` / `channelType` / `limit` filter 返回明确 400。
  - `enabled` / `channelType` / 组合 filter。
  - `limit` 截断时 `truncated=true`，summary 不失真。
  - dry-run 前后关键字段不变，确认 no-write。
  - dry-run 不要求 master key。
  - response 不泄露 raw endpoint / ciphertext / key version / raw secret。
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮是 dry-run，不是真实 backfill。
- 本轮不做 cleanup。
- legacy plaintext 仍允许 fallback send / retry。
- 历史 `notification_channels.endpoint_url` 中已有 plaintext endpoint，本轮未清理。
- 历史 `notification_channels.config_json` 中如已有敏感值，本轮未清理。
- 历史 `notification_deliveries.response_body / failure_reason / payload_json` 中如已有敏感值，本轮未清理。
- 本轮未做 key rotation；如果 master key 丢失或更换，encrypted channel 仍需要后续恢复 / 轮换策略。
- 当前 `docker-compose.yml` 固定 `container_name` 会导致不同 compose project 无法并行启动同一套 EDSP 容器；如需处理，应单独规划 `Docker Compose Container Name Hardening MVP`，不要混入通知业务阶段。

## 下一轮建议

建议下一阶段评估 `Notification Secret Backfill / Cleanup MVP`，但必须先明确 execution / audit / failure recovery / rollback 边界：

- 明确 dry-run 报告如何转为人工批准的执行计划。
- 明确是否允许自动 backfill，或继续只支持人工重配。
- 明确每条迁移记录的审计字段和失败恢复策略。
- 明确失败时不得清空 legacy plaintext `endpoint_url`。
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
