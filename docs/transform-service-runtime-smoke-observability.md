# edsp-transform-service Runtime Smoke & Observability MVP

## 目的

本验证流程在真实 Docker Compose runtime 中，通过 `edsp-core` 的真实 sync API 验证：

- `runtime-mode=remote` 时，`edsp-transform-service` 的 batch transform 结果作为主结果写入。
- `runtime-mode=remote` 且 transform service 不可用时，本次 sync 记录为 `failed`，且不产生 row-level 写入。
- `runtime-mode=fallback` 且 transform service 不可用时，`edsp-core` 回退到 local transform 并完成写入。
- 现有 `report_json.transformRuntime` 字段足以观察上述运行行为。

本轮不新增 production observability 代码，不修改默认 `runtime-mode=local`，不修改 transform HTTP API，也不接入 CI。

## 前置要求

- Docker Desktop 和 `docker compose` 可用。
- 本机不存在已创建的固定名称 EDSP 容器。
- 本机端口 `18080` 和 `18085` 可用。
- 当前代码包含 `edsp-transform-service` runtime deployment 与 switchable runtime 能力。

从仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-transform-runtime-smoke.ps1
```

脚本默认使用：

```text
Compose project: edsp
Database: edsp_transform_runtime_smoke
Schema: transform_runtime_smoke
Frontend URL: http://127.0.0.1:18080
Transform health URL: http://127.0.0.1:18085/actuator/health
```

## 安全边界

`docker-compose.yml` 当前包含固定 `container_name`。因此即使使用不同 compose project，也不能安全地并行启动第二套相同 EDSP runtime。

脚本采取安全优先策略：

- 启动前检查 `edsp-postgres`、`edsp-core`、`edsp-transform-service`、`edsp-gateway`、`edsp-auth`、`edsp-alert`、`edsp-report`、`edsp-frontend`、`edsp-redis` 以及 project `edsp` 关联容器。
- 发现任何已有容器时立即中止。
- 不复用、不停止、不删除既有 runtime。
- 仅在脚本已经启动本次 runtime 后，为失败场景停止并恢复本次 `edsp-transform-service`。
- 执行结束后保留本次容器和验证数据库，便于人工查看日志与数据。

禁止执行：

```powershell
docker compose -p edsp down -v
docker volume rm
docker volume prune
docker rm
```

需要结束脚本启动的服务时，可人工执行非 destructive 停止：

```powershell
docker compose -p edsp stop
```

fixed `container_name` 并行隔离问题不在本轮处理，应在独立的 `Docker Compose Container Name Hardening MVP` 中规划。

## Fixture 策略

脚本通过 `docker compose -p edsp exec -T postgres psql ...` 建立最小 SQL fixture，并通过真实 API 触发 sync。

每个场景使用独立且带唯一 run ID 的：

- source table 与 source row；
- `data_sources`；
- `schema_scan_runs`；
- `schema_tables` / `schema_fields`；
- `ingestion_plans`；
- `ingestion_plan_shadow_runs`；
- `ingestion_plan_activations`；
- `external_id`。

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
脚本停止本次创建的 edsp-transform-service
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

本轮只验证既有字段，不改变 `report_json` schema：

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

### 已有 EDSP 容器

脚本会停止执行并输出冲突容器名称。请先人工确认已有 runtime 是否可以停止。本脚本不会自动改动既有容器。

### 端口已占用

如果 `18080` 或 `18085` 已监听，脚本会在启动容器前失败。排查占用进程后重新执行。

### Docker build 或 startup 失败

查看脚本输出的 `docker compose -p edsp ps -a` 和 `edsp-core` / `edsp-transform-service` 日志。不要通过删除 volume 绕过启动问题。

### Smoke database 无法连接

如果存在历史 `edsp_postgres_data` volume，其初始化数据库名或凭据可能与 smoke 配置不一致。脚本会失败并保留现场，不会自行清理 volume。

### Health endpoint 未 ready

检查：

```powershell
docker compose -p edsp logs edsp-core --tail=200
docker compose -p edsp logs edsp-transform-service --tail=200
```

### Runtime 断言失败

检查 `ingestion_plan_sync_runs.report_json`、`raw_events` 和 `standard_events` 的 smoke 专用记录，确认失败发生在 remote 调用、fallback 选择还是数据写入阶段。

## 为什么不接入 CI

当前固定 `container_name` 使 runtime 隔离不稳定，且 smoke 会有意停启 transform service 并保留检查现场。在解决容器名隔离和清晰的清理策略之前，将该流程加入 GitHub Actions 会引入环境冲突和误清理风险。

后续 CI 化至少需要：

- 移除或参数化 fixed `container_name`；
- 为 smoke runtime 提供可独立创建和释放的资源边界；
- 明确失败后的 artifact / 日志保留与清理策略。
