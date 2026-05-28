# 数据安全预警分析平台 Handoff

更新时间：2026-05-28
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Transform Runtime Smoke Manual Workflow MVP`
- 最新 feature merge commit：`3a7d3aa merge: transform runtime smoke manual workflow mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for transform runtime smoke manual workflow mvp`
- 本轮阶段分支：`codex/transform-runtime-smoke-manual-workflow-mvp`
- 本轮结果：新增 manual-only GitHub Actions workflow，为 runtime smoke 提供 `workflow_dispatch` 手动触发入口；不修改现有 EDSP CI，不挂接 `push` / `pull_request`，不作为自动 CI gate；同时将 runtime smoke 端口检测改为跨平台 `TcpListener`，便于 GitHub-hosted `ubuntu-latest` runner 执行。

## 已完成能力

- 新增普通 Maven library module：`backend/edsp-transform-contract`。
  - 只承载远程 transform HTTP contract DTO。
  - 不依赖 `edsp-core`。
  - 不依赖 `edsp-transform`。
  - 不依赖 `edsp-transform-service`。
  - 不依赖 Spring Web / Spring Boot / JDBC / Flyway / 数据库驱动。
- 新增 Spring Boot service module：`backend/edsp-transform-service`。
  - 依赖 `edsp-transform` 和 `edsp-transform-contract`。
  - 暴露 transform HTTP API：
    - `POST /api/transform/standard-events`
    - `POST /api/transform/standard-events/batch`
  - batch API 保持输入顺序并返回 item `index`。
  - batch 单次最多 100 rows，超过返回 `400 batch_too_large`。
  - invalid request 返回 `400 invalid_transform_request`。
  - 只包装 `StandardEventTransformService.transform(...)`。
  - 不连接数据库，不读取 EDSP 平台库，不读取外部客户数据库，不写任何业务表。
- `edsp-core` 新增对 `edsp-transform-contract` 的单向依赖。
  - `edsp-core -> edsp-transform`
  - `edsp-core -> edsp-transform-contract`
  - `edsp-core` 不依赖 `edsp-transform-service`。
- `edsp-core` 新增 remote shadow client 边界：
  - `TransformRemoteShadowClient`
  - `HttpTransformRemoteShadowClient`
  - `TransformShadowReport`
  - `TransformRemoteShadowConfig`
- remote shadow 默认关闭：
  - `edsp.transform.remote-shadow-enabled=false`
  - 默认关闭时不调用远程服务。
  - 默认关闭时不写 `transformShadow` report 字段，最大限度保持现有 report JSON 不变。
- remote shadow 开启时：
  - `edsp-core` 仍先执行 local transform。
  - local transform 仍是唯一主结果。
  - remote shadow 通过 batch API 做 best-effort compare。
  - remote matched / mismatched / unavailable 只写入现有 sync report JSON。
  - remote mismatch summary 只记录 index / field / type，不记录完整 raw row / source config / secret-like 内容。
  - remote failure 不影响 sync status、raw_events、standard_events、dedup、counts、row failure 语义。
- `backend/Dockerfile` 已更新 build stage COPY 列表：
  - `edsp-transform`
  - `edsp-transform-contract`
  - `edsp-transform-service`
  - 该修改仅保证既有 `docker compose build` 能在 Maven aggregator 下找到新增 module。
- `backend/Dockerfile` 已增加 BuildKit cache / Maven retry 优化：
  - 使用 BuildKit Maven cache mount 缓解 compose 多服务构建时重复下载依赖的问题。
  - 使用 Maven batch / no-transfer-progress / retry 参数提升 Docker build 对 Maven Central read timeout 的容错。
  - 该优化不改变服务运行时语义。
- `docker-compose.yml` 已新增 `edsp-transform-service` runtime service：
  - `SERVICE_MODULE=edsp-transform-service`。
  - `SERVER_PORT=8085`。
  - `NACOS_ENABLED=false`。
  - 不配置 datasource。
  - 不依赖 PostgreSQL。
  - 不依赖 `edsp-core` / `edsp-alert` / `edsp-report`。
  - 不新增 `depends_on`。
  - 端口只绑定 `127.0.0.1:${TRANSFORM_SERVICE_PORT:-18085}:8085`，用于 localhost-only smoke test。
- `docker-compose.yml` 已为 `edsp-core` 增加可选 remote shadow runtime env：
  - `EDSP_TRANSFORM_REMOTE_SHADOW_ENABLED=${EDSP_TRANSFORM_REMOTE_SHADOW_ENABLED:-false}`。
  - `EDSP_TRANSFORM_REMOTE_BASE_URL=${EDSP_TRANSFORM_REMOTE_BASE_URL:-http://edsp-transform-service:8085}`。
  - `EDSP_TRANSFORM_REMOTE_TIMEOUT_MS=${EDSP_TRANSFORM_REMOTE_TIMEOUT_MS:-1000}`。
  - 默认 false，不调用远程 transform service，不影响现有启动和 sync 行为。
- `backend/edsp-transform-service/src/main/resources/application.yml` 已新增最小 runtime 配置：
  - `server.port=${SERVER_PORT:8085}`。
  - `spring.application.name=edsp-transform-service`。
  - actuator 暴露 `health,info`。
  - 不配置 datasource / flyway / nacos / gateway / security。
- `edsp-core` 新增 switchable transform runtime：
  - `TransformRuntimeClient`
  - `LocalTransformRuntimeClient`
  - `RemoteTransformRuntimeClient`
  - `FallbackTransformRuntimeClient`
  - `TransformRuntimeConfig`
  - `TransformRuntimeReport`
- `edsp.transform.runtime-mode` 默认值为 `local`。
  - `local`：继续使用本地 `edsp-transform` Java module，默认不写 `transformRuntime` report，保持既有 report JSON 最大兼容。
  - `remote`：通过 `POST /api/transform/standard-events/batch` 调用 `edsp-transform-service`，remote result 是主结果；remote unavailable / timeout / non-2xx / invalid response 会让本次 sync run `failed`，不会 fallback。
  - `fallback`：优先 remote batch；remote 失败后回退 local，并在 `report_json.transformRuntime` 中记录 `fallbackUsed=true` 和 `failureType`。
- `remote` / `fallback` 只使用 batch transform API，不逐行 HTTP 调用。
- remote transform 调用发生在 row-level DB 写入之前，避免 remote 失败导致半写入。
- `remote-shadow-enabled` 仅在 `runtime-mode=local` 时生效；remote/fallback 模式不执行 shadow compare，避免重复远程调用和 report 语义混乱。
- 空 rows 已保持既有 `no_source_rows` warning 语义：
  - remote/fallback 下不调用 remote API。
  - 不 fallback。
  - 不 failed。
  - 不写 `raw_events` / `standard_events`。
  - remote/fallback 会写 no-rows `transformRuntime`，其中 `remoteAttempted=false`、`remoteSucceeded=false`、`fallbackUsed=false`、无 `failureType`。
- `docker-compose.yml` 已为 `edsp-core` 增加：
  - `EDSP_TRANSFORM_RUNTIME_MODE=${EDSP_TRANSFORM_RUNTIME_MODE:-local}`。
  - 默认仍为 local，不给 `edsp-core` 增加 `depends_on: edsp-transform-service`。
- `IngestionPlanSyncOnceService` 已完成最小 runtime 边界清理：
  - 业务 service 只依赖 `TransformRuntimeClient`。
  - 不再直接依赖 `StandardEventTransformService`、`LocalTransformRuntimeClient`、`MappingPlan`、`TransformOptions` 或 `com.edsp.transform.standardevent.*`。
  - 业务 service 使用 `edsp-transform-contract` DTO 构造 batch request。
  - `TransformRuntimeConfig` / `LocalTransformRuntimeClient` / `TransformContractSupport` 继续承担 engine bridge 职责。
- 新增 `TransformRuntimeDependencyGuardTest`：
  - 扫描 `backend/edsp-core/src/main/java/com/edsp/core`。
  - 仅允许 `config/TransformConfig.java`、`config/TransformRuntimeConfig.java`、`transform/runtime/LocalTransformRuntimeClient.java` 与 `transform/runtime/TransformContractSupport.java` 引用 transform engine。
  - 阻止 `service/**`、`controller/**` 等业务入口重新直接依赖 `StandardEventTransformService` 或 `com.edsp.transform.standardevent.*`。
  - 阻止 `edsp-core` main code 与 `edsp-core/pom.xml` 依赖 `edsp-transform-service`。
- remote/fallback runtime verification 已加强：
  - 使用真实 `RemoteTransformRuntimeClient` 与 JDK `HttpServer` 模拟 batch endpoint。
  - 覆盖 remote success / unavailable / non-2xx / invalid response。
  - 覆盖 fallback remote success / unavailable / non-2xx / invalid response。
  - remote failure 已验证在 row-level 写入前终止，`raw_events=0`、`standard_events=0`，且不会 fallback。
  - fallback remote failure 已验证回退 local 后仍保持 dedup、raw first 与 `standardize_failed` 行为。
- 新增 runtime smoke 手工验证资产：
  - `scripts/verify-transform-runtime-smoke.ps1`
  - `docs/transform-service-runtime-smoke-observability.md`
- runtime smoke 脚本覆盖：
  - `runtime-mode=remote` 且 `edsp-transform-service` 可用时，sync run `passed`，`raw_events=1`，`standard_events=1`，`transformRuntime.remoteSucceeded=true`。
  - `runtime-mode=remote` 且 `edsp-transform-service` 不可用时，sync run `failed`，`raw_events=0`，`standard_events=0`，`transformRuntime.failureType=remote_unavailable`。
  - `runtime-mode=fallback` 且 `edsp-transform-service` 不可用时，sync run `passed`，回退 local transform，`raw_events=1`，`standard_events=1`，`transformRuntime.fallbackUsed=true`。
- runtime smoke 脚本安全边界：
  - 启动前只检测当前 `ComposeProject` 下的容器，发现即中止。
  - 不复用、停止、删除或替换其他 project 的 runtime。
  - 不执行 `docker compose down -v`、`docker volume rm`、`docker volume prune` 或 `docker rm`。
  - 脚本执行后的 smoke 容器和验证数据库默认保留，便于人工检查。
- `docker-compose.yml` 已移除所有固定 `container_name`：
  - `edsp-postgres`
  - `edsp-auth`
  - `edsp-transform-service`
  - `edsp-core`
  - `edsp-alert`
  - `edsp-report`
  - `edsp-gateway`
  - `edsp-frontend`
- Compose service key 保持不变：
  - `postgres`
  - `edsp-auth`
  - `edsp-transform-service`
  - `edsp-core`
  - `edsp-alert`
  - `edsp-report`
  - `edsp-gateway`
  - `frontend`
- Compose 内部 DNS 仍使用 service name，不依赖固定容器名。
- runtime smoke 脚本已改为 project 隔离：
  - 默认 `ComposeProject=edsp_smoke`。
  - 只检测当前 `ComposeProject` 下的容器。
  - 不再全局拦截其他 project 的 EDSP 容器。
  - 保留 host port 检查，避免端口冲突。
  - 所有 compose 操作继续显式使用 `docker compose -p <project>`。
  - 脚本不自动删除容器或 volume；同一 project 复跑会被保留容器拦截，建议使用新的 project / port 或人工确认后处理。
- `docs/transform-service-runtime-smoke-observability.md` 已更新为 project-scoped 诊断方式：
  - 使用 `docker compose -p <project> logs <service>`。
  - 使用 `docker compose -p <project> exec <service> ...`。
  - 不再建议使用固定容器名如 `docker logs edsp-core` / `docker exec edsp-postgres` / `docker stop edsp-core`。
- runtime smoke 已增加 CI-readiness 参数：
  - `-CiMode` 未显式指定 project 时生成唯一 `ComposeProject`。
  - `-CollectLogsOnFailure` 仅在失败时收集有限、project-scoped 日志 artifact。
  - `-FinalAction Stop` 仅执行 `docker compose -p <project> stop`，不删除容器或 volume。
  - 默认 artifact 路径为 `logs/transform-runtime-smoke/<runId>/summary.json`，且 `logs/` 已由 `.gitignore` 忽略。
- 新增 manual-only GitHub Actions workflow：
  - 路径：`.github/workflows/transform-runtime-smoke.yml`。
  - 只支持 `workflow_dispatch` 手动触发。
  - 使用 `ubuntu-latest` 和 PowerShell `pwsh` 执行 runtime smoke。
  - 使用 `-CiMode`、`-CollectLogsOnFailure`、`-FinalAction Stop` 和 `-ReadyAttempts 90`。
  - 上传 `logs/transform-runtime-smoke/**` artifact，retention 为 7 天。
  - 不修改现有 `.github/workflows/ci.yml`。
  - 不挂接 `push` / `pull_request`，不作为自动 CI gate，不设置 required check。
- runtime smoke 脚本端口检测已改为跨平台 `System.Net.Sockets.TcpListener` 探测：
  - 空闲端口允许继续执行。
  - 占用或不可绑定端口会在启动容器前失败。
  - 不再依赖 Windows-only `Get-NetTCPConnection`。

## 明确未做 / 禁止误解

- 本轮不把 `edsp-core` 默认主链路切到 remote / fallback transform。
- 本轮不要求 `edsp-transform-service` 在 runtime 中必须可用。
- 本轮不移除 local transform。
- 本轮不移除 `edsp-core -> edsp-transform` 直接依赖。
- 本轮不修改 `edsp-transform-service` HTTP API。
- 本轮不修改 `edsp-transform-contract` DTO。
- 本轮不新增 database migration。
- 本轮不新增 Gateway route。
- 本轮不新增 Nacos 服务。
- 本轮不改 frontend。
- 本轮不改 `SchemaPage`。
- 本轮不改 `IngestionPlanPanel`。
- 本轮不改 `IngestionPlanShadowRunService`。
- 本轮不改 `IngestionPlanPrecheckService`。
- 本轮不做 transform_rule processor。
- 本轮不做 Standard Field Catalog。
- 本轮不做 Mapping Management Hardening。
- 本轮不新增 MySQL / Oracle / 达梦 / 金仓 connector。
- 本轮不改 rule evaluation。
- 本轮不改 alert generation。
- 本轮不改 notification。
- 本轮不改 alert lifecycle。
- 本轮不重写 historical data。
- 本轮不做 cleanup。
- 本轮不修改 `AGENTS.md`。
- 本轮不修改 `edsp-transform-service` HTTP API 或 `edsp-transform-contract` DTO。
- 本轮不删除 `edsp-core -> edsp-transform` 依赖。
- 本轮不修改 backend / frontend 业务代码。
- 本轮不新增 migration。
- 本轮不接入自动 CI gate。
- 本轮不新增 metrics / structured logging / tracing。
- 本轮不修改 `report_json` schema，只验证已有 `transformRuntime` 字段。
- 本轮仅新增 manual-only GitHub Actions workflow，不修改现有 EDSP CI，不挂接 `push` / `pull_request`。
- 本轮不修改 `docker-compose.yml` 或 backend production Java。

## 当前关键边界

- `edsp-transform-contract` 是纯 DTO contract module。
- `edsp-transform` 仍是纯 Java transform engine。
- `edsp-transform` 不依赖 `edsp-transform-contract`。
- `edsp-transform-service` 是独立服务壳，并已接入 Docker Compose runtime。
- `edsp-transform-service` 仅 localhost-only 暴露 host 端口，不经过 Gateway。
- `edsp-core` 没有 `depends_on: edsp-transform-service`。
- `docker-compose.yml` 不再使用固定 `container_name`；多套 runtime 通过 compose project 隔离容器和 compose-managed volume。
- Compose 内部服务访问必须继续使用 service name，例如 `postgres`、`edsp-core`、`edsp-alert`、`edsp-report`、`edsp-transform-service`。
- `edsp-core` transform runtime 默认仍是 `local`，remote shadow 仍默认关闭。
- `edsp-core` 可通过 `edsp.transform.runtime-mode` / `EDSP_TRANSFORM_RUNTIME_MODE` 显式切换 `local` / `remote` / `fallback`。
- `remote` / `fallback` runtime 依赖 `edsp-transform-service` 可用性，但不会成为默认运行强依赖。
- `edsp-transform-service` 不依赖：
  - `edsp-core`
  - `JdbcTemplate`
  - Flyway
  - PostgreSQL driver
  - mssql-jdbc
  - `data_sources`
  - `raw_events`
  - `standard_events`
  - alerts
  - notifications
  - `IngestionPlanSyncOnceService`
  - `IngestionPlanShadowRunService`
  - `IngestionPlanPrecheckService`
- `edsp-core` 仍负责：
  - 外部数据采样编排
  - `raw_events` 写入
  - `standard_events` 写入
  - dedup 查询
  - raw status 更新
  - ingestion run / sync run 写入
  - sync report 构造
- Remote shadow 只能做 compare，不得改变主链路结果。
- Remote shadow 只在 `runtime-mode=local` 时生效。
- Sync once / scheduled sync 默认行为必须继续以 local transform 为准。
- Remote/fallback runtime 必须继续遵守 raw first、standardize_failed、dedup、counts、report 的既有语义。
- `IngestionPlanSyncOnceService` 等业务入口不得直接依赖 transform engine，必须通过 `TransformRuntimeClient`；该边界由 `TransformRuntimeDependencyGuardTest` 守卫。
- transform engine bridge allowlist 必须保持显式最小范围，不得恢复为允许整个 `transform/runtime/**` 目录任意引用 engine。
- `edsp-core` main code 与 `edsp-core/pom.xml` 均不得依赖 `edsp-transform-service`。
- runtime smoke 的 CI-ready 运行仍是手工 opt-in；`FinalAction=Stop` 仅允许停止本次 project 的容器，不得删除 volume。
- ShadowRun / Precheck 仍保留原逻辑，未来如需统一 transform 判断口径，应单独规划。

## 测试和验证结果

- 阶段分支验证：
  - `mvn -pl edsp-transform -am test` 通过，`8` tests。
  - `mvn -pl edsp-transform-contract -am test` 通过，`1` test。
  - `mvn -pl edsp-transform-service -am test` 通过，`4` tests。
  - `mvn -pl edsp-core -am test` 通过，`144` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `mvn -pl edsp-transform dependency:tree -Dscope=compile` 通过。
  - `mvn -pl edsp-transform-contract dependency:tree -Dscope=compile` 通过。
  - `mvn -pl edsp-transform-service -am dependency:tree -Dscope=compile` 通过。
  - `mvn -pl edsp-core -am dependency:tree "-Dincludes=com.edsp"` 通过。
  - `docker compose -p edsp config` 通过。
  - `docker compose -p edsp build` 通过。
  - `docker compose -p edsp up --build -d edsp-transform-service` 通过。
  - `curl.exe -i http://127.0.0.1:18085/actuator/health` 通过，返回 `HTTP 200` / `{"status":"UP"}`。
  - `POST http://127.0.0.1:18085/api/transform/standard-events/batch` smoke test 通过，返回 `HTTP 200`、`results[0].index=0`、`externalId=ALERT-1`、`severity=high`、`errors=[]`。
  - `git diff --check` 通过。
  - `git status --short --branch` clean after branch commit / push。
- 本轮新增 / 覆盖测试：
  - transform contract DTO defensive copy。
  - single transform HTTP API success。
  - batch transform HTTP API success。
  - batch response preserves input order and index。
  - batch over 100 rows returns `400 batch_too_large`。
  - invalid transform request returns `400 invalid_transform_request`。
  - transform response does not expose DB config / secret-like fields.
  - remote shadow disabled does not call remote client and does not write `transformShadow` report field。
  - remote shadow matched records report but does not change counts or writes。
  - remote shadow mismatch records mismatch without full raw row / config leak。
  - remote shadow unavailable does not fail sync and does not change duplicate / failed / raw / standard counts。
  - runtime-mode local 默认不调用 remote runtime，不写 `transformRuntime`。
  - runtime-mode remote 使用 batch result 作为主结果，且不执行 remote shadow。
  - runtime-mode remote unavailable 在 row-level DB 写入前失败，sync run status = `failed`，不会 fallback。
  - runtime-mode fallback 在 remote failure 时回退 local，并记录 `fallbackUsed=true` / `failureType`。
  - remote runtime 严格校验 batch response size / index / draft / occurredAt。
  - invalid runtime mode 明确失败，不静默 fallback 到 local。
  - remote/fallback 空 rows 不调用 remote，不 fallback，不 failed，保留 `no_source_rows` warning。
  - `IngestionPlanSyncOnceService` 仅通过 `TransformRuntimeClient` 执行 transform。
  - `TransformRuntimeDependencyGuardTest` 防止业务层重新直接引用 transform engine。
  - remote success / unavailable / non-2xx / invalid response 走真实 `RemoteTransformRuntimeClient` 路径完成验证。
  - remote failure 在 row-level DB write 前停止，`raw_events=0`、`standard_events=0`。
  - fallback remote success 使用 remote 主结果；fallback remote failure / invalid response 回退 local。
  - fallback 后仍保持 duplicate detection、raw first 与 `standardize_failed` 语义。
- 本轮 Docker Compose runtime smoke 验证：
  - `docker compose -p edsp config --quiet` 通过。
  - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1` 通过。
  - `Remote success: PASS`。
  - `Remote unavailable: PASS`。
  - `Fallback unavailable: PASS`。
  - `transformRuntime verification: PASS`。
  - `git diff --check` 通过。
  - `git status --short --branch` clean after branch commit / push。
- Docker Compose Container Name Hardening MVP 验证：
  - `docker compose -p edsp config` 通过。
  - `docker compose -p edsp_smoke config` 通过。
  - `docker compose -p edsp_smoke_b config` 通过。
  - `Select-String -Path docker-compose.yml -Pattern "container_name"` 无输出。
  - `docker compose -p edsp config | findstr /i "container_name"` 无输出。
  - `docker compose -p edsp_smoke config | findstr /i "container_name"` 无输出。
  - `docker compose -p edsp_smoke_b config | findstr /i "container_name"` 无输出。
  - `powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 -ComposeProject edsp_smoke_verify -FrontendPort 18082 -TransformPort 18087` 通过：
    - `Remote success: PASS`
    - `Remote unavailable: PASS`
    - `Fallback unavailable: PASS`
    - `transformRuntime verification: PASS`
  - `mvn -pl edsp-core -am test` 通过，`144` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `git diff --check` 通过；仅有 Git line-ending warning，无 whitespace error。
  - `git status --short --branch` clean after branch commit / push。
- Transform Runtime Readiness & Guard MVP 合并前验证：
  - `mvn -pl edsp-core -am test` 通过，`146` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `docker compose -p edsp config --quiet` 通过。
  - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 -CiMode -FrontendPort 18120 -TransformPort 18125 -FinalAction Stop` 通过：
    - 自动生成唯一 project：`edsp_smoke_ci_20260527233528_25ed9fd3`。
    - `Remote success: PASS`。
    - `Remote unavailable: PASS`。
    - `Fallback unavailable: PASS`。
    - `transformRuntime verification: PASS`。
    - `FinalAction=Stop` 仅停止并保留 smoke 容器与 volume。
  - `git diff --check` 通过。
  - 范围检查确认仅阶段 test / smoke script / smoke doc 发生代码内容变更，不包含 production Java、frontend、migration、`docker-compose.yml` 或 GitHub Actions 变更。
- Transform Runtime Smoke Manual Workflow MVP 合并前验证：
  - workflow 静态检查通过：`.github/workflows/transform-runtime-smoke.yml` 只包含 `workflow_dispatch`，不包含 `push` / `pull_request` / `schedule`。
  - 确认未修改现有 `.github/workflows/ci.yml`。
  - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 -CiMode -FrontendPort 18140 -TransformPort 18145 -FinalAction Stop` 通过：
    - `Remote success: PASS`。
    - `Remote unavailable: PASS`。
    - `Fallback unavailable: PASS`。
    - `transformRuntime verification: PASS`。
  - `mvn -pl edsp-core -am test` 通过，`146` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `docker compose -p edsp config --quiet` 通过。
  - `git diff --check` 通过。
  - 阶段分支 review 期间不要求真实 `workflow_dispatch` run，因为新增 workflow 文件需先进入 default branch。
- Transform Runtime Smoke Manual Workflow MVP post-merge 手动验证：
  - GitHub Actions workflow：`Transform Runtime Smoke`。
  - 触发方式：manual `workflow_dispatch`。
  - 运行分支：`master`。
  - Run URL：`https://github.com/Ruidooww/EDSP/actions/runs/26549739874`。
  - Job URL：`https://github.com/Ruidooww/EDSP/actions/runs/26549739874/job/78209198316`。
  - 运行 commit：`6266964`。
  - 运行结果：`Success`。
  - 运行环境：`ubuntu-24.04` / `ubuntu-latest`。
  - 总耗时：`3m21s`。
  - Artifact：`transform-runtime-smoke-26549739874-1`。
  - Artifact ID：`7256068684`。
  - Artifact size：`474 Bytes`。
  - Artifact expires：`2026-06-04`。
  - Artifact 内容确认：仅包含 `summary.json`。
  - `summary.json` 验证结果：
    - `remoteSuccess=PASS`。
    - `remoteUnavailable=PASS`。
    - `fallbackUnavailable=PASS`。
    - `transformRuntimeVerification=PASS`。
  - Artifact 安全边界确认：未包含 DB dump、完整 raw row、`data_sources.config_json`、完整 env 或 secret-like 内容。
  - 当前 warning：`actions/upload-artifact@v4` 存在 GitHub 平台 Node.js 20 deprecation warning，不影响本次成功，后续可作为 P2 跟踪。
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮已新增 `edsp-transform-service` Docker Compose runtime。
- 本轮没有新增 Gateway / Nacos / service discovery。
- 本轮没有把 `edsp-core` 默认主链路切换到 remote / fallback transform。
- Remote shadow 仍默认关闭；如手动开启，可通过 compose 内部地址 `http://edsp-transform-service:8085` 访问 transform service。
- runtime-mode 仍默认 `local`；如手动切到 `remote` / `fallback`，需要确保 `edsp-transform-service` 在 runtime 中可用。
- remote/fallback 已通过手工 Docker Compose runtime smoke 脚本验证核心场景；脚本现具备 CI-ready 参数与有限 artifact 采集能力，并已提供 manual-only GitHub Actions 入口。
- 本轮 runtime verification 使用 JDK `HttpServer` 驱动真实 remote client，未新增 Docker e2e 框架。
- 固定 `container_name` 已移除，日常 runtime 与 smoke runtime 可通过不同 compose project 并行存在，前提是 host port 不冲突。
- 当前 runtime smoke 脚本只检查当前 `ComposeProject` 下的容器；不会再全局拦截其他 project 的 EDSP 容器。
- runtime smoke 脚本执行后不会自动删除容器或 volume，保留现场用于人工检查。
- 当前仍不自动删除 volume；如需清理 smoke 容器 / volume，必须单独人工确认，且不得使用默认 destructive 命令。
- `IngestionPlanShadowRunService` 和 `IngestionPlanPrecheckService` 暂未接入 `edsp-transform` / remote shadow。
- 后续如果要让 remote/fallback 成为推荐运行模式，需要单独规划 runtime smoke、观测、回滚和运维边界。
- Runtime smoke 已提供 `workflow_dispatch` 手动入口，但仍不是自动 CI gate；未挂接 `push` / `pull_request`，也不是 required check。
- `Transform Runtime Smoke` 已在合并到 `master` 后完成第一次 manual `workflow_dispatch` 验证，结果为 `Success`，artifact 已确认仅包含 `summary.json`。
- `TransformRuntimeDependencyGuardTest` 已收紧为显式 bridge allowlist；后续新增 runtime bridge 需要显式审查并更新守卫，不能通过扩大目录豁免绕过边界。

## 下一轮建议

建议下一阶段按需评估自动化 CI gate：

```text
Transform Runtime Smoke Auto CI Gate Evaluation MVP
```

目标建议：

- 评估是否将 manual-only runtime smoke 扩展为 `push` / `pull_request` 自动 CI gate。
- 明确是否作为 required check，以及 runner 资源、端口分配、artifact retention、失败现场保留与非破坏性清理策略。
- 如果暂不设置 required check，继续保持 manual workflow 作为人工验证入口。
- 继续保持 `runtime-mode=local` 默认值。
- 不新增 Gateway / Nacos / service discovery。
- 不修改 transform runtime 业务语义。
- 不执行 destructive volume cleanup。

如果优先统一转换判断口径，可单独排期：

- `Standard Event Transform Shadow/Precheck Alignment MVP`

如继续推进 transform runtime 观测，可在 compose 隔离完成后单独排期：

- `Transform Runtime Structured Logging MVP`
- `Transform Runtime Metrics MVP`

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
