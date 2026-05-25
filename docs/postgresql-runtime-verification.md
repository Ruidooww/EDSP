# PostgreSQL Runtime Verification

本文记录 EDSP 本地 Docker Compose + PostgreSQL 真实运行态验证流程。目标是验证项目自身 runtime 能否稳定启动并通过 Flyway migration、服务日志、数据库表检查和 HTTP smoke test。

本验证不是外部业务库接入阶段，不连接旧预警平台库，不做第三方 schema discovery，不新增业务功能。

## 验证目标

- PostgreSQL 容器能启动并进入 healthy。
- `edsp-core` 能连接 PostgreSQL 并从空库执行 Flyway migration 到最新版本。
- `edsp-alert`、`edsp-report`、`edsp-auth`、`edsp-gateway`、`frontend` 能随 compose 启动。
- `jsonb` 和 `timestamptz` 字段能在 PostgreSQL 上正常创建。
- 核心表可查询：`notification_channels`、`notification_deliveries`、`alerts`、`alert_lifecycle_events`、`raw_events`、`standard_events`、`alert_decisions`。
- 前端经 nginx 代理访问 gateway 后，核心只读接口返回 200。

## 启动前安全检查

启动 Docker 前必须先检查当前项目容器状态：

```powershell
docker compose ps
```

如果发现已有 EDSP 容器正在运行，先报告当前状态。不要直接执行带 `-v` 的 compose down 命令，因为它会删除 volume。

禁止删除 `postgres_data` volume，禁止默认清空已有数据库，禁止手动改库绕过 Flyway。

建议使用独立 compose project 和独立临时数据库名做 clean PostgreSQL 验证，避免污染默认 `edsp` 数据库：

```powershell
$env:POSTGRES_DB = "edsp_pg_verify"
$env:POSTGRES_USER = "edsp"
$env:POSTGRES_PASSWORD = "edsp-demo-password"

docker compose -p edsp-pg-verify ps
```

## Docker Desktop PATH 注意事项

在 Codex 进程中，如果刚安装 Docker Desktop，当前进程的 `PATH` 可能尚未刷新，直接执行 `docker` 可能报：

```text
The term 'docker' is not recognized
```

可在当前 PowerShell 会话临时补充 Docker CLI 路径：

```powershell
$env:Path = "C:\Program Files\Docker\Docker\resources\bin;C:\Program Files\Docker\cli-plugins;" + $env:Path
```

如果 Docker build 拉取镜像时报 Docker Hub token 超时，例如：

```text
failed to fetch oauth token
```

先确认这是网络或 Docker Hub 访问问题，不要修改项目代码或 migration。可先预拉取基础镜像后重试：

```powershell
docker pull postgres:16-alpine
docker pull eclipse-temurin:21-jre
docker pull maven:3.9.9-eclipse-temurin-21
docker pull node:24-alpine
docker pull nginx:1.27-alpine
```

## 启动命令

先启动 PostgreSQL：

```powershell
$env:POSTGRES_DB = "edsp_pg_verify"
$env:POSTGRES_USER = "edsp"
$env:POSTGRES_PASSWORD = "edsp-demo-password"

docker compose -p edsp-pg-verify up --build -d postgres
docker compose -p edsp-pg-verify ps
docker compose -p edsp-pg-verify logs postgres --tail=100
```

确认 PostgreSQL healthy 后启动应用服务：

```powershell
docker compose -p edsp-pg-verify up --build -d edsp-core edsp-alert edsp-report edsp-auth edsp-gateway frontend
docker compose -p edsp-pg-verify ps
```

## 日志检查命令

```powershell
docker compose -p edsp-pg-verify logs postgres --tail=100
docker compose -p edsp-pg-verify logs edsp-core --tail=200
docker compose -p edsp-pg-verify logs edsp-alert --tail=200
docker compose -p edsp-pg-verify logs edsp-report --tail=200
docker compose -p edsp-pg-verify logs edsp-auth --tail=200
docker compose -p edsp-pg-verify logs edsp-gateway --tail=200
docker compose -p edsp-pg-verify logs frontend --tail=100
```

期望结果：

- PostgreSQL 日志显示 database system is ready to accept connections。
- `edsp-core` 日志显示 Flyway validated / migrated 成功。
- `edsp-alert`、`edsp-report`、`edsp-gateway` 日志显示 started。
- `frontend` nginx 日志显示 Configuration complete; ready for start up。

## 数据库检查命令

Flyway migration 检查：

```powershell
docker compose -p edsp-pg-verify exec -T postgres psql -U edsp -d edsp_pg_verify -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

表数量检查：

```powershell
docker compose -p edsp-pg-verify exec -T postgres psql -U edsp -d edsp_pg_verify -c "select count(*) as table_count from information_schema.tables where table_schema='public';"
```

核心表存在性检查：

```powershell
docker compose -p edsp-pg-verify exec -T postgres psql -U edsp -d edsp_pg_verify -c "select table_name from information_schema.tables where table_schema='public' and table_name in ('notification_channels','notification_deliveries','alerts','alert_lifecycle_events','raw_events','standard_events','alert_decisions') order by table_name;"
```

`jsonb` / `timestamptz` 字段检查：

```powershell
docker compose -p edsp-pg-verify exec -T postgres psql -U edsp -d edsp_pg_verify -c "select table_name, column_name, data_type from information_schema.columns where table_schema='public' and data_type in ('jsonb','timestamp with time zone') order by table_name, column_name limit 40;"
```

核心表行数检查：

```powershell
docker compose -p edsp-pg-verify exec -T postgres psql -U edsp -d edsp_pg_verify -c "select 'notification_channels' as table_name, count(*) from notification_channels union all select 'notification_deliveries', count(*) from notification_deliveries union all select 'alerts', count(*) from alerts union all select 'alert_lifecycle_events', count(*) from alert_lifecycle_events union all select 'raw_events', count(*) from raw_events union all select 'standard_events', count(*) from standard_events union all select 'alert_decisions', count(*) from alert_decisions;"
```

## HTTP Smoke Test

Docker Compose 默认只暴露 frontend：

```text
http://localhost:18080
```

frontend nginx 将 `/api/` 代理到 `edsp-gateway`，因此 HTTP smoke test 建议从 `18080` 进入：

```powershell
curl.exe -i http://localhost:18080
curl.exe -i http://localhost:18080/api/core/overview
curl.exe -i http://localhost:18080/api/notifications/channels
curl.exe -i "http://localhost:18080/api/notifications/deliveries?limit=10"
curl.exe -i "http://localhost:18080/api/notifications/deliveries?limit=10&status=success&channelType=webhook"
curl.exe -i "http://localhost:18080/api/notifications/deliveries?limit=10&alertId=1&status=success&channelType=webhook&channelId=1"
curl.exe -i "http://localhost:18080/api/core/alerts?limit=10"
curl.exe -i http://localhost:18080/api/reports/jobs
```

非法参数也应返回明确 400：

```powershell
curl.exe -i "http://localhost:18080/api/notifications/deliveries?status=bogus"
curl.exe -i "http://localhost:18080/api/notifications/deliveries?channelType=bogus"
```

## 本轮实际验证结果

验证日期：2026-05-25

使用独立 compose project：

```text
edsp-pg-verify
```

使用独立 PostgreSQL 数据库：

```text
edsp_pg_verify
```

结果摘要：

- `docker compose -p edsp-pg-verify ps` 显示 `edsp-postgres` 为 `healthy`，`edsp-core`、`edsp-alert`、`edsp-report`、`edsp-auth`、`edsp-gateway`、`edsp-frontend` 均为 `Up`。
- `edsp-core` 在真实 PostgreSQL 16.14 上成功验证 14 个 Flyway migrations，并从空 schema 迁移到 v14。
- `flyway_schema_history` 中 v1 到 v14 均为 `success = true`。
- `public` schema 表数量为 32。
- `notification_channels`、`notification_deliveries`、`alerts`、`alert_lifecycle_events`、`raw_events`、`standard_events`、`alert_decisions` 均存在。
- `jsonb` 与 `timestamp with time zone` 字段存在并可查询。
- HTTP smoke test 通过：
  - `GET /` -> 200
  - `GET /api/core/overview` -> 200
  - `GET /api/notifications/channels` -> 200
  - `GET /api/notifications/deliveries?limit=10` -> 200
  - `GET /api/notifications/deliveries?limit=10&status=success&channelType=webhook` -> 200
  - `GET /api/notifications/deliveries?limit=10&alertId=1&status=success&channelType=webhook&channelId=1` -> 200
  - `GET /api/core/alerts?limit=10` -> 200
  - `GET /api/reports/jobs` -> 200
  - invalid `status` -> 400 `invalid_delivery_status`
  - invalid `channelType` -> 400 `unsupported_channel`

## 常见问题

### 端口占用

frontend 默认映射：

```text
18080:80
```

如果本机 `18080` 被占用，可通过环境变量调整：

```powershell
$env:FRONTEND_PORT = "18081"
```

### 容器名冲突

`docker-compose.yml` 当前使用固定 `container_name`，例如 `edsp-postgres`、`edsp-core`、`edsp-frontend`。即使使用不同 compose project，也可能因为固定容器名冲突。

如果已有同名 EDSP 容器在运行，不要直接删除或 `down -v`。先报告当前状态，确认是否可停止旧容器。

### volume 已存在

不要删除 `postgres_data` 或项目已有 volume。做 clean migration 验证时，优先使用独立 compose project 和独立临时数据库名。

### Flyway migration 失败

先检查：

```powershell
docker compose -p edsp-pg-verify logs edsp-core --tail=200
docker compose -p edsp-pg-verify exec -T postgres psql -U edsp -d edsp_pg_verify -c "select * from flyway_schema_history order by installed_rank;"
```

只有确认是当前仓库 migration/config 兼容问题时，才做最小修复。不得跳过 migration，不得手动改库绕过 Flyway，不得注释 migration。

### PostgreSQL 账号密码不一致

确认 compose 环境变量一致：

```powershell
$env:POSTGRES_DB
$env:POSTGRES_USER
$env:POSTGRES_PASSWORD
```

### gateway 代理失败

Docker Compose 中 gateway 默认不直接暴露本机端口。通过 frontend nginx 访问：

```text
http://localhost:18080/api/...
```

如果 `/api/` 返回 5xx，检查：

```powershell
docker compose -p edsp-pg-verify logs edsp-gateway --tail=200
docker compose -p edsp-pg-verify logs edsp-core --tail=200
docker compose -p edsp-pg-verify logs edsp-alert --tail=200
docker compose -p edsp-pg-verify logs edsp-report --tail=200
```

## 明确禁止

- 不要执行 `docker compose down -v` 清空 volume。
- 不要删除 `postgres_data` volume。
- 不要默认清空已有 `edsp` 数据库。
- 不要手动改库绕过 Flyway。
- 不要跳过 Flyway migration。
- 不要为了跑通而注释 migration。
- 不要在本阶段接外部业务库或旧预警平台库。
- 不要把 runtime verification 扩展成新业务功能。
