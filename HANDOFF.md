# 数据安全预警分析平台 Handoff

更新时间：2026-05-26
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Notification Secret Backfill Execution MVP`
- 最新 feature merge commit：`d96fdd2 merge: notification secret backfill execution mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for notification secret backfill execution mvp`
- 本轮阶段分支：`codex/notification-secret-backfill-execution-mvp`
- 本轮结果：已在 dry-run 能力基础上新增受控、可审计、逐条执行的 notification secret backfill execution；只迁移用户显式选择且执行时重新校验仍 eligible 的 legacy plaintext channels。

## 已完成能力

- 新增 V17 migration：`V17__notification_secret_backfill_audit.sql`。
  - 新增 `notification_secret_backfill_runs`。
  - 新增 `notification_secret_backfill_items`。
  - 新增 run / item 查询索引。
  - 增加兼容 PostgreSQL 和 H2 PostgreSQL mode 的基础 CHECK 约束。
  - 不修改 V16 字段定义。
  - 不删除、不重命名、不清空旧 `notification_channels.endpoint_url` 字段。
  - 不修改既有业务表历史数据。
- 新增执行 API：`POST /api/notifications/secret-backfill/execute`。
  - 只接受用户显式选择的 `channelIds`。
  - 单次最多 50 个 channel。
  - 要求强确认短语：`EXECUTE_NOTIFICATION_SECRET_BACKFILL`。
  - `confirmation` 原文不写入 audit table，也不返回给 API consumer。
  - `requestedBy` 缺省使用 `manual`，不伪造为 `admin`。
- 新增审计查询 API：
  - `GET /api/notifications/secret-backfill/runs`
  - `GET /api/notifications/secret-backfill/runs/{id}`
  - run list 支持 `limit=1..100` 和 `status=running|completed|completed_with_failures|failed`。
  - run detail 返回 run summary 和 item audit 明细。
- 执行语义：
  - execution 必须要求 master key 存在且合法。
  - master key missing 返回 `400 notification_secret_key_missing`。
  - master key invalid 返回 `400 notification_secret_key_invalid`。
  - master key missing / invalid 在 run creation 前失败，不创建 run，不写 item。
  - 执行时重新读取当前 DB 状态，不信任旧 dry-run 结果。
  - 每条 channel 执行前重新判断：
    - channel exists。
    - `secret_storage_status == legacy_plaintext`。
    - `endpoint_url` present。
    - `channel_type` supported。
    - `endpoint_url` valid for channel type。
  - 只迁移 `legacy_plaintext + endpoint_url present + endpoint valid`。
  - 成功迁移后才清空该 channel 的 `endpoint_url`。
  - skipped / failed item 不清空 `endpoint_url`，不写 ciphertext，不改变 `secret_storage_status`。
- 成功迁移写入：
  - `endpoint_secret_ciphertext`。
  - `endpoint_secret_key_version = local-v1`。
  - `endpoint_masked`。
  - `secret_storage_status = encrypted`。
  - `endpoint_url = null`。
  - `updated_at = now()`。
- 审计语义：
  - 每次 execute 写 run audit。
  - 每个 channel 写 item audit。
  - item status 支持 `migrated` / `skipped` / `failed`。
  - failure reason 覆盖 `not_found`、`already_encrypted`、`not_legacy_plaintext`、`endpoint_missing`、`endpoint_invalid`、`unsupported_channel_type`、`notification_secret_store_failed`、`unexpected_error` 等。
  - run 最终状态支持 `completed` / `completed_with_failures` / `failed`。
  - 单条迁移使用独立事务处理。
  - 单条失败不回滚其他已成功迁移 channel。
  - run 创建后 best-effort finalize，避免正常异常路径留下长期 `running`。
- response / audit secret safety：
  - audit table 不保存 raw `endpoint_url`。
  - audit table 不保存 `endpoint_secret_ciphertext`。
  - audit table 不保存 `endpoint_secret_key_version`。
  - execute / run list / run detail response 不返回 raw endpoint / ciphertext / key version / confirmation 原文。
  - `endpoint_masked` 继续经过 sanitizer / mask 兜底。
- 前端 `NotificationsPage` 在既有 `密钥回填 Dry Run` 面板中新增受控执行入口：
  - 只能选择 `migration_eligible` / `migrationEligible=true` 的 dry-run item。
  - blocked / encrypted / missing items 不可选择。
  - 未选择时执行按钮禁用。
  - 单次最多选择 50 个 channel。
  - 执行前必须输入 `EXECUTE_NOTIFICATION_SECRET_BACKFILL`。
  - 确认短语不匹配时不调用 execute API。
  - 执行后刷新 dry-run 和 channels list。
  - 展示本次 run summary / item result。
  - 展示最近 runs 的只读摘要。

## 明确未做 / 禁止误解

- 本轮是 execution，不是 cleanup。
- 未清理历史 `notification_channels.config_json`。
- 未清理历史 `notification_deliveries`。
- 未重写 delivery response / failure / payload。
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
- 未修改 Docker / compose。
- 未修改 `AGENTS.md`。
- 未提供一键全量迁移全部 eligible。
- 未提供 cleanup 按钮。
- 未提供批量清空 / 批量禁用 / 批量重配入口。
- 未提供 reveal / copy raw endpoint / download secret。

## 当前关键边界

- backfill execution 只允许修改用户显式选择且执行时重新校验仍 eligible 的 `notification_channels` 行。
- 成功迁移后会清空对应 channel 的 `endpoint_url`，这是本轮唯一允许的 legacy endpoint 清空行为。
- skipped / failed item 必须保留原 channel 数据。
- execution audit 不得保存 raw endpoint / ciphertext / key version / confirmation 原文。
- dry-run 仍然只读，不要求 master key，不调用 secret store。
- 通知发送入口仍只能是 `POST /api/notifications/alerts/send`，请求边界仍是 `alertId + channelId`。
- retry 入口仍只能是 `POST /api/notifications/deliveries/{id}/retry`。
- Notification delivery 只能从 existing `alerts` 出发，不能直接从 `standard_events` / `alert_decisions` / raw payload 发送。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。
- Rule evaluation 不得直接创建 alerts 或发送 notifications。
- Alert generation 只能从 matched `alert_decisions` 创建 alerts。
- `GET /api/notifications/channels` 不得返回 `endpoint_url` / `endpoint_secret_ciphertext` / `endpoint_secret_key_version`。
- `endpoint_masked` 返回前必须继续经过 sanitizer 兜底。

## 测试和验证结果

- 阶段分支验证：
  - `mvn -pl edsp-alert -am test` 通过，`100` tests。
  - `mvn -pl edsp-core -am test` 通过，`118` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `git diff --check` 通过。
  - `git status --short --branch` clean after branch commit / push。
- 本轮新增 / 覆盖测试：
  - V17 migration 在 H2 PostgreSQL mode 下通过。
  - audit tables、字段、索引和 CHECK 约束基础兼容性。
  - execute request validation。
  - master key missing / invalid。
  - successful migration for webhook / wecom / feishu。
  - skipped items 不修改原 channel。
  - per-item failure isolation。
  - run finalize。
  - no historical cleanup。
  - run list / run detail / execute response secret-safe。
  - controller route / invalid request / run not found。
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮只迁移用户选择的 eligible legacy plaintext channels。
- 历史 `notification_channels.config_json` 如已有敏感值，本轮未清理。
- 历史 `notification_deliveries.response_body / failure_reason / payload_json` 如已有敏感值，本轮未清理。
- legacy plaintext 未被选择或执行时不符合 eligible 条件的 channel 仍会保留 fallback。
- 本轮未做 key rotation；如果 master key 丢失或更换，encrypted channel 仍需要后续恢复 / 轮换策略。
- 当前 `docker-compose.yml` 固定 `container_name` 会导致不同 compose project 无法并行启动同一套 EDSP 容器；如需处理，应单独规划 `Docker Compose Container Name Hardening MVP`，不要混入通知业务阶段。

## 下一轮建议

建议下一阶段进入 `Notification Secret Historical Cleanup Planning MVP` 或 `Notification Secret Historical Cleanup MVP`，但必须先明确审计、回滚、报表影响和数据保留边界：

- 是否允许清理历史 `notification_channels.config_json` 中的敏感值。
- 是否允许清理历史 `notification_deliveries.response_body / failure_reason / payload_json`。
- 是否需要 cleanup dry-run 报告和人工确认。
- cleanup 是否需要单独 audit table 或复用现有审计模型。
- cleanup 失败时如何恢复、如何证明未扩大泄露面。
- 继续保持 send / retry / alert lifecycle / rule evaluation / alert generation 语义不变。
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
