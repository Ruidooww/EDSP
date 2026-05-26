# 数据安全预警分析平台 Handoff

更新时间：2026-05-26
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Alerts Page Table Layout Hotfix`
- 最新 feature merge commit：`1d18b1f merge: alerts page table layout hotfix`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for alerts page table layout hotfix`
- 本轮阶段分支：`codex/alerts-page-table-layout-hotfix`
- 本轮结果：已修复前端告警中心告警列表表格布局问题，避免告警标题、中文字段、状态和操作按钮被压缩成竖排，恢复横向滚动、可读列宽和正常行高。

## 已完成能力

- 前端 `AlertsPage` 表格布局修复：
  - 为告警标题、等级、状态、规则、Decision ID、Standard Event、指派给、用户、资产、发生时间、更新时间、操作列设置明确宽度。
  - 将告警列表横向滚动宽度提升到 `scroll={{ x: 2200 }}`，避免浏览器压缩列宽导致中文逐字换行。
  - 告警标题列固定宽度并限制在单行展示，长标题通过 ellipsis 省略。
  - 规则、用户、资产、指派人、时间等长文本字段统一使用受控 ellipsis。
  - 操作列保持 `fixed: 'right'`，宽度调整为 `360`，按钮保持横向排列。
- 局部 CSS 防护：
  - 仅在 `.alerts-table` 作用域下增加 `white-space: nowrap`、ellipsis 和 action button nowrap 防护。
  - 不影响 `NotificationsPage` / `DashboardPage` / 其他表格。
- 保留现有告警操作：
  - 详情
  - 确认
  - 指派
  - 关闭
  - 发送通知

## 明确未做 / 禁止误解

- 未改后端。
- 未新增 migration。
- 未修改 alert lifecycle。
- 未修改 alert status 语义。
- 未修改 assign / acknowledge / close API。
- 未修改 notification send / retry。
- 未修改 Notification Secret Storage。
- 未修改 rule evaluation。
- 未修改 alert generation。
- 未修改 sync-once / scheduled sync。
- 未修改 Docker / compose。
- 未修改 `AGENTS.md`。
- 未新增业务入口。
- 未删除现有功能按钮。

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
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `git diff --check` 通过；无 whitespace error。
  - `git status --short --branch` clean after branch commit / push。
- 手工 UI 验证结论：
  - 告警列表不再依赖自动压缩列宽。
  - 告警标题列有固定宽度和 ellipsis。
  - 表格启用横向滚动。
  - 操作列按钮保持可见且横向排列。
  - 详情 / 确认 / 指派 / 关闭 / 发送通知等已有操作未删除。
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮未启动真实后端数据做浏览器截图验证；如需要，可后续用真实 alert 数据在 1366px / 1440px 宽度下补一轮视觉确认。
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
