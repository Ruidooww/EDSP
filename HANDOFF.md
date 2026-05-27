# 数据安全预警分析平台 Handoff

更新时间：2026-05-27
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`edsp-transform-service Runtime Deployment MVP`
- 最新 feature merge commit：`04521c7 merge: edsp transform service runtime deployment mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for edsp transform service runtime deployment mvp`
- 本轮阶段分支：`codex/edsp-transform-service-runtime-deployment-mvp`
- 本轮结果：已将 `edsp-transform-service` 接入 Docker Compose runtime，可构建、启动、健康检查和本地 smoke test；`edsp-core` 在 compose runtime 中具备默认关闭的 remote shadow 环境配置，但主链路仍保持 local transform。

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

## 明确未做 / 禁止误解

- 本轮不把 `edsp-core` 主链路切到 remote transform。
- 本轮不要求 `edsp-transform-service` 在 runtime 中必须可用。
- 本轮不新增 `edsp.transform.mode=remote`。
- 本轮不新增 `edsp.transform.mode=fallback`。
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

## 当前关键边界

- `edsp-transform-contract` 是纯 DTO contract module。
- `edsp-transform` 仍是纯 Java transform engine。
- `edsp-transform` 不依赖 `edsp-transform-contract`。
- `edsp-transform-service` 是独立服务壳，并已接入 Docker Compose runtime。
- `edsp-transform-service` 仅 localhost-only 暴露 host 端口，不经过 Gateway。
- `edsp-core` 没有 `depends_on: edsp-transform-service`。
- `edsp-core` 主链路仍是 local transform，remote shadow 仍默认关闭。
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
- Sync once / scheduled sync 行为必须继续以 local transform 为准。
- ShadowRun / Precheck 仍保留原逻辑，未来如需统一 transform 判断口径，应单独规划。

## 测试和验证结果

- 阶段分支验证：
  - `mvn -pl edsp-transform -am test` 通过，`8` tests。
  - `mvn -pl edsp-transform-contract -am test` 通过，`1` test。
  - `mvn -pl edsp-transform-service -am test` 通过，`4` tests。
  - `mvn -pl edsp-core -am test` 通过，`120` tests。
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
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮已新增 `edsp-transform-service` Docker Compose runtime。
- 本轮没有新增 Gateway / Nacos / service discovery。
- 本轮没有把 `edsp-core` 主链路切换到 remote transform。
- Remote shadow 仍默认关闭；如手动开启，可通过 compose 内部地址 `http://edsp-transform-service:8085` 访问 transform service。
- 当前 `docker-compose.yml` 固定 `container_name` 会导致不同 compose project 无法并行启动同一套 EDSP 容器；如需处理，应单独规划 `Docker Compose Container Name Hardening MVP`。
- `IngestionPlanShadowRunService` 和 `IngestionPlanPrecheckService` 暂未接入 `edsp-transform` / remote shadow。
- 后续如果要真正启用 remote transform，需要单独规划 runtime、fallback、观测和回滚边界。

## 下一轮建议

如果继续推进 transform service 主线，建议下一阶段进入：

```text
edsp-transform-service Switchable Runtime MVP
```

目标建议：

- 在保持安全默认值的前提下，评估 `local` / `remote` / `fallback` runtime mode。
- 明确 remote transform 不可用时的失败 / fallback / report 语义。
- 保持默认仍为 local transform，避免 runtime 服务不可用影响现有 sync。
- 继续不新增 transform_rule processor。
- 不改 rule evaluation / alert generation / notification / alert lifecycle。

如果优先统一转换判断口径，可单独排期：

- `Standard Event Transform Shadow/Precheck Alignment MVP`

如果优先处理工程治理，可单独排期：

- `Docker Compose Container Name Hardening MVP`

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
