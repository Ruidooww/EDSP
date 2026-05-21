# 数据安全预警分析平台 handoff

> 更新时间：2026-05-21
> 当前提交：`6f44ad8 feat: implement core event pipeline`
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 1. 项目定位

本项目是“数据安全预警分析平台”，不是 IP-Guard 专用适配器，也不是单纯数据库读取工具。

当前技术底座必须继续围绕下面这条主链路推进：

```text
外部系统 / 数据库 / API / Webhook / 文件 / Syslog / Agent
  -> 采集适配器
  -> raw_events / raw_logs / raw_imports
  -> 字段映射与标准化
  -> standard_events
  -> 规则 / 风险判断
  -> alerts
  -> 通知 / 处置 / 报表 / 反馈
```

权威约束文档是 `TECHNICAL_MANUAL.md`。后续除非项目负责人明确调整，否则不要偏离该手册定义的产品方向、数据链路和架构边界。

## 2. 当前技术栈

| 层级 | 当前选型 |
|---|---|
| 后端 | Java 21 / Spring Boot 3.3.5 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.1.2 |
| 网关 | Spring Cloud Gateway |
| 数据库 | PostgreSQL 16，Flyway 管理迁移 |
| 本地开发库 | H2 PostgreSQL mode |
| 前端 | React 18 / TypeScript / Ant Design 5 / Vite |
| 部署 | Docker Compose |

后端模块：

```text
backend/
  edsp-common   公共响应模型
  edsp-gateway 统一 API 网关
  edsp-auth    登录和账号信息
  edsp-core    数据源、元数据、采集、raw/standard 事件、总览
  edsp-alert   告警、规则、通知
  edsp-report  报表任务
```

## 3. 最新完成内容

最近一次提交：

```text
6f44ad8 feat: implement core event pipeline
```

这次提交主要完成：

- 新增 `raw_events` / `standard_events` / `ingestion_runs` 等核心事件链路结构。
- 新增采集任务服务 `CollectionTaskService`。
- 新增事件写入服务 `IngestionService`。
- 新增元数据扫描和变更检测服务：
  - `JdbcMetadataScanService`
  - `SchemaScanService`
- 完善 `SchemaController`、`SchemaScanController`、`CollectionTaskController`、`IngestionController`。
- 前端完善元数据快照、采集任务执行反馈和类型定义。
- 新增 `CollectionTaskServiceTest`，覆盖采集任务从元数据映射到 raw/standard 事件的闭环。
- 新增审查记录：`docs/reviews/2026-05-20-core-event-pipeline-review.md`。

## 4. 当前可运行状态

本地开发模式可使用 H2，不依赖 Docker。

后端启动：

```powershell
.\scripts\start-local-backend.ps1
```

前端启动：

```powershell
cd frontend
npm install
npm run dev
```

默认访问：

```text
前端：http://localhost:5173/
网关：http://localhost:8080/
core health：http://localhost:8082/actuator/health
```

Docker Compose 部署：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

默认 Docker 前端端口由 `.env` 的 `FRONTEND_PORT` 控制，当前 `.env.example` 默认值是 `18080`。

## 5. 已验证结果

本地最近一次验证结果：

```powershell
mvn -pl edsp-core -am test
```

结果：通过。

```text
Tests run: 1, Failures: 0, Errors: 0
BUILD SUCCESS
```

```powershell
mvn -pl edsp-core -am package -DskipTests
```

结果：通过。

```powershell
npm run build
```

结果：通过。保留 Vite chunk size warning，当前不是阻塞项。

本地服务验证：

```powershell
(Invoke-RestMethod -Uri 'http://localhost:8082/actuator/health').status
```

结果：`UP`。

采集闭环验证：

```text
POST /api/core/collection-tasks/{id}/runs?runType=manual
```

验证结果：

```json
{
  "status": "success",
  "readCount": 1,
  "successCount": 1,
  "failedCount": 0,
  "standardizedCount": 1
}
```

已确认该执行会产生：

- 1 条 `raw_events`
- 1 条 `standard_events`
- 1 条成功的 `ingestion_runs`

## 6. 当前菜单和页面方向

当前前端信息架构应保持：

```text
总览
数据源
  - 数据源管理
  - 元数据快照
  - 外部接入
  - 采集任务
告警中心
规则中心
通知中心
报表
设置
```

注意：

- `元数据快照` 是数据源下的二级能力。
- 字段映射应整合到 `元数据快照`，不要作为孤立主流程。
- `外部接入` 和 `采集任务` 也属于数据源体系下的二级能力。
- 规则中心不能要求客户手写底层字段名或表达式作为默认体验，后续应做模板化和参数化。

## 7. 已知问题和风险

当前仍需处理：

1. 认证和鉴权仍是占位能力，后续必须补 Spring Security + JWT 或等价方案。
2. 密码、Webhook token、API token 等敏感信息后续不能明文保存。
3. 规则中心还没有真正的规则执行引擎。
4. 通知中心目前以配置和展示为主，真实企微、飞书、短信、邮件投递链路还要补。
5. 报表中心仍需补真实统计、导出内容和任务调度。
6. 当前字段映射模板还不够完整，部分标准字段可能为空，例如 `actor`、`asset_ref`、`action`。
7. 演示数据和正式数据必须隔离，不能把 demo seed 当作生产数据来源。
8. 大库扫描和新增字段处理需要继续完善自动识别、置信度、低风险自动归档和高风险变更提醒。
9. `agent.md` 当前是未跟踪文件，最近一次 commit 没有包含它。

## 8. 下一步建议

优先级建议：

1. 完善元数据自动识别和字段映射模板。
   - 目标：客户不需要逐表逐字段录入。
   - 做法：基于表名、字段名、样例值、注释和已有模板生成推荐映射。

2. 完善新增字段处理策略。
   - 默认不阻塞采集。
   - 高置信度字段自动进入 `standard_events` 标准字段或扩展字段。
   - 低置信度字段进入待确认队列。
   - 只有影响风险判断的字段变更才触发运营提醒。

3. 实现规则模板化。
   - 规则中心不直接暴露 `file_size > 104857600 && after_hours == true` 这类表达式给客户。
   - 改为场景模板，例如“大文件外发”“非工作时间下载”“敏感文件传输”。
   - 客户只填阈值、时间范围、人员范围、数据源范围等参数。

4. 打通 `standard_events -> alert_rules -> alerts`。
   - 当前已完成 raw/standard 基础链路。
   - 下一步应让规则基于标准事件产生真实告警。

5. 完善通知投递闭环。
   - Webhook 优先。
   - 后续扩展企业微信、飞书、短信、邮件。
   - 所有投递结果进入 `notification_deliveries`。

6. 加固生产部署能力。
   - 密钥加密存储。
   - 权限控制。
   - 审计日志。
   - PostgreSQL 初始化和迁移验证。
   - Docker Compose 环境变量和健康检查。

## 9. 常用命令

查看工作区状态：

```powershell
git status --short
```

后端测试：

```powershell
cd backend
mvn -pl edsp-core -am test
```

后端打包：

```powershell
cd backend
mvn -pl edsp-core -am package -DskipTests
```

前端构建：

```powershell
cd frontend
npm run build
```

停止本地后端：

```powershell
.\scripts\stop-local-backend.ps1
```

查看本地后端状态：

```powershell
.\scripts\status-local-backend.ps1
```

Docker 部署：

```powershell
docker compose up -d --build
docker compose ps
docker compose logs --tail=200 edsp-core
```

## 10. 给下一位开发者的注意事项

- 不要把 IP-Guard 当成唯一目标，IP-Guard 只是首个数据库类外部源样例。
- 不要绕过 raw 层直接生成告警。
- 不要让客户逐字段维护映射作为默认交付流程。
- 不要把 demo 数据、mock 数据、临时逻辑混入正式链路。
- 修改数据库结构必须走 Flyway migration。
- 修改接口后同步更新前端 `frontend/src/types.ts`。
- 提交前至少跑：

```powershell
cd backend
mvn -pl edsp-core -am test

cd ..\frontend
npm run build
```
