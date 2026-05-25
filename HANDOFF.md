# 数据安全预警分析平台 Handoff

更新时间：2026-05-25
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`PostgreSQL Runtime Verification MVP`
- 最新 feature merge commit：`6ab9e33 merge: postgresql runtime verification mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for postgresql runtime verification mvp`
- 本轮阶段分支：`codex/postgresql-runtime-verification-mvp`
- 本轮结果：已完成 EDSP 项目自身 PostgreSQL runtime 验证收口；新增运行态验证文档，确认 Docker Compose、PostgreSQL、Flyway、后端服务、gateway、frontend 和核心只读业务链路在真实 PostgreSQL 下可启动、可迁移、可访问。

## 已完成能力

- 新增 `docs/postgresql-runtime-verification.md`，记录 PostgreSQL runtime verification 的安全流程、启动命令、日志检查、数据库检查、HTTP smoke test、常见问题和禁止事项。
- 使用独立 compose project `edsp-pg-verify` 和独立验证数据库 `edsp_pg_verify`，避免默认清空或污染已有 `edsp` 数据库。
- 验证 Docker Compose 下 `postgres` 可启动并进入 `healthy`。
- 验证 `edsp-core` 可连接 PostgreSQL 16.14，并从空库执行 Flyway v1-v14 到最新版本。
- 验证 `flyway_schema_history` 中 14 条 migration 均为 success。
- 验证 `public` schema 下核心表存在，包括 `notification_channels`、`notification_deliveries`、`alerts`、`alert_lifecycle_events`、`raw_events`、`standard_events`、`alert_decisions`。
- 验证 PostgreSQL 中 `jsonb` 与 `timestamp with time zone` 字段可创建并查询。
- 验证 `edsp-alert`、`edsp-report`、`edsp-auth`、`edsp-gateway`、`frontend` 均可随 Docker Compose 启动。
- 验证 frontend nginx 可通过 `http://localhost:18080/api/...` 代理到 gateway，并访问 core / alert / report 只读接口。
- 验证通知投递筛选接口在真实 PostgreSQL runtime 下可查询 `limit`、`alertId`、`status`、`channelType`、`channelId` 组合参数。

## 明确未做 / 禁止误解

- 未接入外部业务库。
- 未接入旧预警平台库。
- 未做第三方数据库 schema discovery。
- 未新增业务功能。
- 未新增 notification channel adapter。
- 未做 Notification Delivery Reliability。
- 未做手动重放。
- 未做自动重试。
- 未做失败队列。
- 未做权限审计。
- 未做 Dashboard。
- 未修改 alert lifecycle 语义。
- 未修改 notification sending 语义。
- 未修改 `POST /api/notifications/alerts/send` 的 `alertId + channelId` 边界。
- 未修改 `alerts` / `alert_decisions` / `standard_events` 语义。
- 未修改 sync-once / scheduled sync / rule evaluation / alert generation 语义。
- 未引入 Kafka / Redis / ClickHouse / AI。
- 未修改 `AGENTS.md`。
- 未新增 migration。
- 未修改业务代码。

## 当前关键边界

- 本阶段只是 EDSP 自身 PostgreSQL runtime 验证，不是外部数据源接入阶段。
- Docker 验证前必须先执行 `docker compose ps`，发现已有 EDSP 容器运行时必须先报告状态。
- 不得默认执行会删除 volume 的 compose down 命令。
- 不得删除 `postgres_data` volume。
- 不得默认清空已有数据库。
- clean PostgreSQL migration 验证优先使用独立临时数据库名或独立临时容器。
- Flyway migration 失败时必须先定位并报告原因，不得跳过 migration，不得手动改库绕过 Flyway。
- Notification delivery 仍只能从已有 `alerts` 出发。
- 手动通知入口仍为 `POST /api/notifications/alerts/send`，请求体仍只允许 `alertId + channelId`。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。

## 测试结果

- Docker Compose：`edsp-pg-verify` 下 `postgres`、`edsp-core`、`edsp-alert`、`edsp-report`、`edsp-auth`、`edsp-gateway`、`frontend` 均 `Up`；`postgres` 为 `healthy`。
- PostgreSQL / Flyway：PostgreSQL 16.14 空库迁移到 Flyway v14；14 条 migration 均 success。
- 数据库检查：核心表存在；`jsonb` 与 `timestamp with time zone` 字段存在并可查询。
- HTTP smoke：`GET /`、`GET /api/core/overview`、`GET /api/notifications/channels`、`GET /api/notifications/deliveries?limit=10`、组合投递筛选、`GET /api/core/alerts?limit=10`、`GET /api/reports/jobs` 均返回 200。
- HTTP error smoke：非法 `status` 返回 400 `invalid_delivery_status`；非法 `channelType` 返回 400 `unsupported_channel`。
- 后端 alert：`cd backend; mvn -pl edsp-alert -am test` 通过，46 tests，0 failures，BUILD SUCCESS。
- 后端 core：`cd backend; mvn -pl edsp-core -am test` 通过，112 tests，0 failures，BUILD SUCCESS。
- 前端：`cd frontend; npm.cmd run build` 通过，仅有既有 Vite chunk size warning。
- Git 检查：阶段分支 `git diff --check` 无 whitespace error；post-merge docs 更新将再次检查。
- 代码审查：PostgreSQL Runtime Verification MVP review 已完成；发现 1 个 P1 文档安全问题，已修复；当前无 P0 / P1 遗留。

## 已知后续项

- Docker Desktop 刚安装后，Codex 进程可能需要临时补充 Docker CLI 路径；文档已记录处理方式。
- 首次 Docker build 可能遇到 Docker Hub token / 网络超时；文档已记录预拉取基础镜像的排查方式。
- `docker-compose.yml` 使用固定 `container_name`，多 project 并行验证时仍可能发生容器名冲突；后续如需更强隔离，可单独阶段调整 compose 命名策略。
- PostgreSQL runtime verification 当前是文档化流程，尚未封装成一键脚本；如果后续频繁验证，可单独阶段补最小非破坏性 helper。

## 下一轮建议

下一阶段建议回到业务主线，进入 `Notification Delivery Reliability MVP`。

建议范围：

- 继续保持手动通知入口和 `alertId + channelId` 边界。
- 为 `notification_deliveries` 增加结构化失败原因、可重放标记或最小重试记录前，先明确 migration 和状态模型。
- 不做自动通知，不做全量群发，不做告警升级，不做通知编排，不接入 AI / Kafka / Redis / ClickHouse。

备选方向：

- 如果优先完善部署质量，可继续做 Docker/PostgreSQL runtime helper，但必须保持不删除 volume、不清空数据库、不绕过 Flyway 的边界。
