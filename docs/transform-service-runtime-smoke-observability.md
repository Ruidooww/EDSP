# edsp-transform-service Runtime Smoke & Observability MVP

## 目的

本验证流程在真实 Docker Compose runtime 中，通过 `edsp-core` 的真实 sync API 验证：

- `runtime-mode=remote` 时，`edsp-transform-service` 的 batch transform 结果作为主结果写入。
- `runtime-mode=remote` 且 transform service 不可用时，本次 sync 记录为 `failed`，且不产生 row-level 写入。
- `runtime-mode=fallback` 且 transform service 不可用时，`edsp-core` 回退到 local transform 并完成写入。
- 现有 `report_json.transformRuntime` 字段足以观测上述运行行为。

本轮不新增 production observability 代码，不修改默认 `runtime-mode=local`，不修改 transform HTTP API，也不接入自动 CI gate。完成 compose project 隔离后，自动 CI gate 化可以作为后续独立阶段。

## 前置要求

- Docker Desktop 和 `docker compose` 可用。
- 本机端口 `18080` 和 `18085` 可用。
- 当前代码包含 `edsp-transform-service` runtime deployment、switchable runtime 能力，以及 Docker Compose Container Name Hardening MVP 的 compose project 隔离改动。

从仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1
```

脚本默认使用：

```text
Compose project: edsp_smoke
Database: edsp_transform_runtime_smoke
Schema: transform_runtime_smoke
Frontend URL: http://127.0.0.1:18080
Transform health URL: http://127.0.0.1:18085/actuator/health
```

如需指定 project 或端口，应通过脚本参数传入，并保持所有诊断命令使用同一个 compose project。

## Compose Project 隔离

Docker Compose 中固定的 `container_name` 已移除。runtime/smoke runtime 现在通过 compose project 隔离容器和 volume，不再依赖固定容器名。

默认 smoke project 为 `edsp_smoke`。日常 runtime 和 smoke runtime 可以共存，前提是 host port 不冲突。脚本只检查当前 `ComposeProject` 的容器，不再因为其他 project 中存在 EDSP 容器而中止。

这意味着：

- `edsp`、`edsp_smoke`、`edsp_smoke_a`、`edsp_smoke_b` 等 project 的容器名由 Docker Compose 自动加 project 前缀。
- 不同 project 拥有独立的 compose-managed volume，避免 smoke 数据污染日常 runtime 数据。
- 脚本的冲突检测范围限定在当前 `ComposeProject`，不会停止、删除或复用其他 project 的容器。
- 如果两个 project 映射到相同 host port，Docker 仍会因为端口冲突导致启动失败。

并行运行示例：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 -ComposeProject edsp_smoke_a -FrontendPort 18080 -TransformPort 18085
powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 -ComposeProject edsp_smoke_b -FrontendPort 18081 -TransformPort 18086
```

诊断时统一使用 project-scoped compose 命令，例如：

```powershell
docker compose -p edsp_smoke ps -a
docker compose -p edsp_smoke logs edsp-core --tail=200
docker compose -p edsp_smoke logs edsp-transform-service --tail=200
docker compose -p edsp_smoke exec -T postgres psql -U edsp -d edsp_transform_runtime_smoke
docker compose -p edsp_smoke stop
```

不要使用固定容器名进行诊断或操作，例如 `docker logs edsp-core`、`docker exec edsp-postgres`、`docker stop edsp-core`。

## 安全边界

脚本采取安全优先策略：

- 启动前只检查当前 `ComposeProject` 关联容器。
- 发现当前 `ComposeProject` 已有容器时中止，避免复用或覆盖同一 project 的历史现场。
- 不复用、不停止、不删除其他 project 的 runtime。
- 仅在脚本已经启动本次 runtime 后，为失败场景停止并恢复本次 `edsp-transform-service`。
- 执行结束后保留本次容器和验证数据库，便于人工查看日志与数据。

禁止执行：

```powershell
docker compose -p <project> down -v
docker volume rm
docker volume prune
docker rm
```

默认只建议在需要结束脚本启动的服务时，人工执行非 destructive 停止：

```powershell
docker compose -p <project> stop
```

注意：`stop` 会保留容器，因此同一个 `ComposeProject` 复跑仍会被脚本拦截。再次执行 smoke 时，优先使用新的 `ComposeProject` 和未占用端口。本轮默认只建议 `stop`。如需删除 smoke 容器，应单独人工确认，并不得使用 `-v`。

## Fixture 策略

脚本通过 `docker compose -p <project> exec -T postgres psql ...` 建立最小 SQL fixture，并通过真实 API 触发 sync。

每个场景使用独立且带唯一 run ID 的：

- source table 与 source row
- `data_sources`
- `schema_scan_runs`
- `schema_tables` / `schema_fields`
- `ingestion_plans`
- `ingestion_plan_shadow_runs`
- `ingestion_plan_activations`
- `external_id`

source table 位于专用 schema：

```text
transform_runtime_smoke
```

source data 仅包含验证所需字段：

```text
id
create_time
event_name
user_account
host_name
risk_level
```

脚本不会直接插入 `ingestion_plan_sync_runs`、`raw_events` 或 `standard_events` 来模拟结果。真正执行入口为：

```text
POST /api/core/ingestion-plan-activations/{activationId}/sync-once
```

## Smoke 场景

### 1. Remote Success

运行条件：

```text
EDSP_TRANSFORM_RUNTIME_MODE=remote
edsp-transform-service 可用
```

期望：

```text
ingestion_plan_sync_runs.status = passed
transformRuntime.mode = remote
transformRuntime.remoteAttempted = true
transformRuntime.remoteSucceeded = true
transformRuntime.fallbackUsed = false
transformRuntime.failureType 不存在
raw_events = 1
standard_events = 1
standard_events.external_id 与 fixture 一致
standard_events.severity = high
standard_events.actor = runtime-smoke-user
```

### 2. Remote Unavailable

运行条件：

```text
EDSP_TRANSFORM_RUNTIME_MODE=remote
脚本停止本次 project 创建的 edsp-transform-service
```

期望：

```text
ingestion_plan_sync_runs.status = failed
transformRuntime.mode = remote
transformRuntime.remoteAttempted = true
transformRuntime.remoteSucceeded = false
transformRuntime.fallbackUsed = false
transformRuntime.failureType = remote_unavailable
该 fixture 的 raw_events = 0
该 fixture 的 standard_events = 0
```

此路径当前可能仍返回 HTTP `200` 和一条 `status=failed` 的 sync run 数据。脚本断言 response body 和数据库状态，不要求 HTTP 为非 `2xx`。

### 3. Fallback Unavailable

运行条件：

```text
EDSP_TRANSFORM_RUNTIME_MODE=fallback
edsp-transform-service 保持不可用
```

期望：

```text
ingestion_plan_sync_runs.status = passed
transformRuntime.mode = fallback
transformRuntime.remoteAttempted = true
transformRuntime.remoteSucceeded = false
transformRuntime.fallbackUsed = true
transformRuntime.failureType = remote_unavailable
raw_events = 1
standard_events = 1
standard_events.external_id 与 fixture 一致
standard_events.severity = high
standard_events.actor = runtime-smoke-user
```

`fallback remote success` 语义已由后端测试覆盖，本手工 runtime smoke 不重复扩展该场景。

## 观测字段

本轮只验证既有字段，不改动 `report_json` schema：

| 字段 | 含义 |
| --- | --- |
| `mode` | 本次运行使用的 runtime 模式：`remote` 或 `fallback` |
| `remoteAttempted` | 是否尝试调用远程 transform service |
| `remoteSucceeded` | 远程 transform 是否返回可用主结果 |
| `fallbackUsed` | 是否在远程失败后使用 local transform |
| `failureType` | 远程失败分类；成功路径不应出现 |

本轮不新增：

```text
structured logging
Actuator metrics
Micrometer / Prometheus
tracing
新的 production report 字段
```

若运行验证证明现有字段不足，再单独规划 `Transform Runtime Structured Logging MVP` 或 `Transform Runtime Metrics MVP`。

## 常见失败原因

### 当前 project 已有 EDSP 容器

脚本会停止执行并输出当前 `ComposeProject` 下的冲突容器。本脚本不会自动改动已有容器，也不会因为其他 project 的 EDSP 容器存在而中止。`docker compose -p <project> stop` 会保留容器，因此不足以让同一个 project 复跑；再次执行 smoke 时，优先使用新的 `ComposeProject` 和未占用端口。

建议检查：

```powershell
docker compose -p edsp_smoke ps -a
docker compose -p edsp_smoke logs edsp-core --tail=200
```

需要结束当前 project 服务时：

```powershell
docker compose -p edsp_smoke stop
```

本轮默认只建议 `stop`。如需删除 smoke 容器，应单独人工确认，并不得使用 `-v`。

### 端口已占用

如果 `18080` 或 `18085` 已监听，脚本会在启动容器前失败。日常 runtime 和 smoke runtime 共存时，请为 smoke 指定未占用的 host port。

例如第二套 smoke runtime 使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 -ComposeProject edsp_smoke_b -FrontendPort 18081 -TransformPort 18086
```

### Docker build 或 startup 失败

查看脚本输出中的 project-scoped 状态和日志。不要通过删除 volume 绕过启动问题。

```powershell
docker compose -p edsp_smoke ps -a
docker compose -p edsp_smoke logs edsp-core --tail=200
docker compose -p edsp_smoke logs edsp-transform-service --tail=200
```

### Smoke database 无法连接

检查当前 project 的 `postgres` 服务和数据库：

```powershell
docker compose -p edsp_smoke ps postgres
docker compose -p edsp_smoke logs postgres --tail=200
docker compose -p edsp_smoke exec -T postgres psql -U edsp -d edsp_transform_runtime_smoke
```

不同 compose project 使用独立 volume。不要通过 `docker compose -p <project> down -v`、`docker volume rm` 或 `docker volume prune` 清理数据。

### Health endpoint 未 ready

检查：

```powershell
docker compose -p edsp_smoke logs edsp-core --tail=200
docker compose -p edsp_smoke logs edsp-transform-service --tail=200
```

### Runtime 断言失败

检查 `ingestion_plan_sync_runs.report_json`、`raw_events` 和 `standard_events` 的 smoke 专用记录，确认失败发生在 remote 调用、fallback 选择还是数据写入阶段。

```powershell
docker compose -p edsp_smoke exec -T postgres psql -U edsp -d edsp_transform_runtime_smoke
```

## 为什么本轮仍不接入自动 CI gate

本轮已通过移除 fixed `container_name` 并使用 compose project 隔离，解决了 runtime/smoke runtime 的基础容器名冲突问题。

但 runtime smoke 仍会启动真实 Docker Compose 服务、改动本次 project 中的 transform service 可用性，并保留容器与数据库现场供人工排查。为了避免把这些资源竞争、日志保留策略和清理策略风险直接变成 PR / `master` 合并阻塞，本轮只提供 manual-only workflow，不接入自动 CI gate。

后续自动 CI gate 化至少需要：

- 明确 CI 中的 project 命名、host port 分配和并发策略。
- 明确失败后的 artifact / 日志保留策略。
- 明确安全清理策略，且不得默认执行 destructive volume 删除。
- 将脚本运行时间、Docker build cache 和服务 ready 超时纳入 CI 预算。

## CI Readiness Options

本脚本已经具备 CI 化前置参数。本轮提供 manual-only GitHub Actions workflow：`Transform Runtime Smoke`，但它只通过 `workflow_dispatch` 手动触发，不是自动 CI gate。

本轮不做以下接入：

- 不挂 `push` / `pull_request`。
- 不修改现有 `EDSP CI`。
- 不设置 required check。
- 不作为 PR 或 `master` 合并保护条件。

关于是否将 manual workflow 升级为自动 CI gate，请参考：

```text
docs/transform-runtime-smoke-ci-gate-evaluation.md
```

若未来要把 runtime smoke 作为 PR / `master` 合并保护，需要单独阶段设计和实施。

推荐的 CI-ready 手工命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 -CiMode
```

`-CiMode` 行为：

- 未显式传入 `-ComposeProject` 时，自动生成唯一 project：`edsp_smoke_ci_<runId>`。
- 未显式传入 `-FinalAction` 时，默认使用 `Stop`。
- 默认启用失败日志采集。
- 写入 summary artifact：`logs/transform-runtime-smoke/<runId>/summary.json`。

可选参数：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1 `
  -CiMode `
  -FrontendPort 18080 `
  -TransformPort 18085 `
  -ReadyAttempts 90 `
  -ArtifactRoot logs/transform-runtime-smoke
```

`summary.json` 只记录聚合验证信息：

```text
runId
composeProject
frontendPort
transformPort
ciMode
finalAction
scenarios
failureStage
failureType
failureMessage
warnings
```

失败时脚本会采集有限日志到 artifact 目录：

```text
ps.txt
logs-postgres.txt
logs-edsp-core.txt
logs-edsp-transform-service.txt
logs-edsp-gateway.txt
logs-frontend.txt
summary.json
```

日志采集仍使用 project-scoped compose 命令，不读取或导出数据库 volume，不执行 destructive cleanup。

### GitHub Actions manual workflow

注意：GitHub Actions 的 `workflow_dispatch` 手动触发要求 workflow 文件已经存在于 default branch。
因此，本阶段分支 review 期间不要求真实 Actions 手动运行通过；阶段分支内只验证 YAML、脚本兼容性、本地 smoke 和范围边界。
合并到 `master` 后，再从 GitHub Actions 页面手动触发 `Transform Runtime Smoke`，并记录 run URL、运行结果和 artifact。

在 GitHub UI 手动运行：

1. 打开仓库的 `Actions` 页面。
2. 选择 `Transform Runtime Smoke` workflow。
3. 点击 `Run workflow`。
4. 选择要验证的 branch。
5. 点击 `Run workflow` 启动本次 runtime smoke。

该 workflow 的 artifact：

```text
Name: transform-runtime-smoke-${{ github.run_id }}-${{ github.run_attempt }}
Path: logs/transform-runtime-smoke/**
Retention: 7 days
```

artifact 包含 `summary.json` 和有限 project-scoped logs。artifact 不包含：

```text
DB dump
完整 raw row
data_sources.config_json
完整 env
secret-like 内容
```

GitHub-hosted runner 结束后，运行环境由 GitHub Actions 平台回收；脚本自身仍只执行 non-destructive cleanup，也就是 `docker compose -p <project> stop`。

`-FinalAction` 支持：

```text
Keep
Stop
```

- `Keep`：保留本次 runtime 容器，便于人工检查。
- `Stop`：执行 `docker compose -p <project> stop`，只停止容器，不删除容器或 volume。

脚本仍禁止：

```powershell
docker compose -p <project> down -v
docker volume rm
docker volume prune
docker rm
```

正式接入自动 PR / `master` 保护前，仍需单独确认：

- runner 资源是否足够完成 `docker compose build` 与 smoke runtime。
- host port 是否固定可用，或是否改为 CI 专用端口策略。
- artifact 保留策略是否需要不同于当前 manual workflow 的 7 days。
- smoke 失败后是否需要人工保留现场，或只保留 `Stop` 后的日志 artifact。
- 是否需要把 runtime smoke 升级为 PR / `master` 合并保护，避免影响常规 backend/frontend 快速验证。

本轮不修改 production runtime 行为，不新增 metrics / structured logging / tracing，也不修改 `report_json` schema。
