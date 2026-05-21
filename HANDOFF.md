# 数据安全预警分析平台 handoff

> 更新时间：2026-05-21  
> 当前分支：`master`  
> 当前 HEAD：`2d6f70d merge origin/master`  
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 1. 项目定位

本项目是“数据安全预警分析平台”，不是 IP-Guard 专用平台，也不是单纯数据库读取工具。

核心产品目标是：连接本地化系统、数据库、API、Webhook、文件、Syslog、Agent 等外部来源，持续采集安全事件，标准化为平台统一事件，再生成告警、推送通知、处置审计和报表。

当前主链路必须保持：

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

## 3. 当前工作区状态

本轮已完成 hardening，但尚未 commit。

`git status --short` 当前包含：

```text
 M backend/edsp-core/src/main/java/com/edsp/core/controller/DataSourceController.java
 M backend/edsp-core/src/main/java/com/edsp/core/dto/SchemaScanFinishRequest.java
 M backend/edsp-core/src/main/java/com/edsp/core/service/CollectionTaskService.java
 M backend/edsp-core/src/main/java/com/edsp/core/service/IngestionService.java
 M backend/edsp-core/src/main/java/com/edsp/core/service/JdbcMetadataScanService.java
 M backend/edsp-core/src/main/java/com/edsp/core/service/SchemaScanService.java
 M backend/edsp-core/src/test/java/com/edsp/core/service/CollectionTaskServiceTest.java
?? agent.md
?? backend/edsp-core/src/main/java/com/edsp/core/service/StandardEventDedupService.java
?? backend/edsp-core/src/main/resources/db/migration/V7__schema_scan_coverage.sql
?? backend/edsp-core/src/test/java/com/edsp/core/controller/
?? backend/edsp-core/src/test/java/com/edsp/core/service/SchemaScanServiceTest.java
?? docs/superpowers/
```

注意：`agent.md` 是未跟踪文件，本轮没有修改它，也不应在未确认用途前纳入提交。

建议本轮提交信息：

```text
fix: harden metadata scan coverage and event dedup
```

## 4. 本轮已完成内容

### 4.1 metadata scan coverage hardening

已修复问题：

- limited scan 不再执行 `markMissingObjects()`。
- 避免真实数据库有大量表时，因为 `tableLimit` / `fieldLimit` 只扫到部分对象，就把历史 active 表/字段误判为 `removed`。
- `schema_scan_runs` 增加结构化覆盖率字段：
  - `limited`
  - `coverage_rate`
- `total_tables` 现在表示真实可用表数。
- `scanned_tables` 表示本次实际扫描表数。
- `JdbcMetadataScanService.MetadataScanResult` 现在携带：
  - `totalAvailableTables`
  - `limited`
  - `coverageRate`
  - `totalAvailableFields`

新增迁移：

```text
backend/edsp-core/src/main/resources/db/migration/V7__schema_scan_coverage.sql
```

### 4.2 event dedup hardening

已修复问题：

- `CollectionTaskService` 以前只按 `source_system + external_id` 查重。
- `IngestionService` 已按 `dedup_key` 查重，两边逻辑不一致。
- 现在新增 `StandardEventDedupService`，统一查重顺序：

```text
先查 dedup_key
再查 source_system + external_id
```

涉及文件：

```text
backend/edsp-core/src/main/java/com/edsp/core/service/StandardEventDedupService.java
backend/edsp-core/src/main/java/com/edsp/core/service/IngestionService.java
backend/edsp-core/src/main/java/com/edsp/core/service/CollectionTaskService.java
```

### 4.3 DataSourceController hardening

已修复问题：

- 不存在的数据源 ID 不再因 `queryForMap` 抛异常导致 500。
- `testConnection()`、`tables()`、`columns()`、`sample()` 统一使用 `sourceRow()`。
- 数据源不存在时返回：

```text
404 Data source not found: {id}
```

- `create()` 拿不到 generated id 时直接抛异常，不再返回 `id=0`。

涉及文件：

```text
backend/edsp-core/src/main/java/com/edsp/core/controller/DataSourceController.java
```

## 5. 本轮新增测试

新增：

```text
backend/edsp-core/src/test/java/com/edsp/core/service/SchemaScanServiceTest.java
backend/edsp-core/src/test/java/com/edsp/core/controller/DataSourceControllerTest.java
```

扩展：

```text
backend/edsp-core/src/test/java/com/edsp/core/service/CollectionTaskServiceTest.java
```

覆盖点：

- limited scan 不标记历史 active 对象为 removed。
- full scan 仍然可以标记真实缺失对象为 removed。
- `schema_scan_runs.total_tables / scanned_tables / limited / coverage_rate` 写入正确。
- `CollectionTaskService` 优先按 `dedup_key` 查重。
- 数据源 missing ID 返回 404。
- 数据源 create 返回正数 generated id。

## 6. 已验证结果

定向测试：

```powershell
cd backend
mvn -pl edsp-core "-Dtest=SchemaScanServiceTest,CollectionTaskServiceTest,DataSourceControllerTest" test
```

结果：通过。

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

受影响模块完整测试：

```powershell
cd backend
mvn -pl edsp-core -am test
```

结果：通过。

```text
Reactor Summary:
edsp-platform SUCCESS
edsp-common   SUCCESS
edsp-core     SUCCESS

Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

本轮没有重新运行前端构建，因为改动集中在后端 hardening 和后端测试。

## 7. 当前可运行方式

本地后端：

```powershell
.\scripts\start-local-backend.ps1
```

本地前端：

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

Docker Compose：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

## 8. 当前菜单和页面方向

前端信息架构应保持：

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

## 9. 下一步主线

当前 hardening 已完成，下一步应进入：

```text
feat: add database intelligence ingestion plan MVP
```

建议按以下顺序推进：

1. Database Inventory
   - 扫描数据库、schema、表、字段、字段类型、nullable、row_count、modified_time。
   - 后续补 primary key / index。

2. Coverage Verification
   - 回答“数据库一共有多少表、本次扫到多少、是否被 limit 截断、是否有失败、哪些没扫、为什么没扫”。

3. Semantic Profiler
   - 识别字段语义：

```text
external_id
occurred_at
severity
actor
asset_ref
title
action
subject_ref
sensitive_value
plain
```

4. Template Matcher
   - 做通用模板，不写死某个厂家：

```text
告警表模板
日志表模板
用户表模板
终端表模板
策略表模板
详情表模板
```

5. Ingestion Plan Generator
   - 生成完整接入方案：
     - `mode`
     - `confidence`
     - `mainTable`
     - `cursorField`
     - `idField`
     - `fieldMappings`
     - `syncStrategy`
     - `risks`

6. Shadow Validator
   - 先生成 shadow standard events / decisions。
   - 不正式推送。
   - 只出试运行报告。

## 10. 暂不优先事项

当前不要直接跳到：

```text
standard_events -> alert_rules -> alerts
```

原因：规则闭环依赖稳定、可信、可核验的数据库识别和接入方案。现在应先保证平台能准确回答：

```text
这个库一共有多少表？
我扫到了多少表？
有没有漏扫？
哪些表像告警表？
哪些字段像时间、用户、等级、终端？
这个接入方案可信度多少？
能不能先试运行？
```

这些问题解决后，再做规则引擎更稳。

## 11. 已知风险和后续硬化点

1. 认证和鉴权仍需补 Spring Security + JWT 或等价方案。
2. 密码、Webhook token、API token 等敏感信息不能长期明文保存。
3. 通知中心目前以配置和展示为主，真实企微、飞书、短信、邮件投递链路还要补。
4. 报表中心仍需补真实统计、导出内容和任务调度。
5. 字段映射模板还不完整，后续要补样本值识别、表上下文识别、confidence 和 reason。
6. 演示数据和正式数据必须隔离，不能把 demo seed 当作生产数据来源。
7. `includeViews`、按 database 范围扫描、多数据库 source scope 后续还需要继续明确覆盖率语义。

## 12. 常用命令

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

## 13. 给下一位开发者的注意事项

- 不要把 IP-Guard 当成唯一目标，IP-Guard 只是首个数据库类外部源样例。
- 不要绕过 raw 层直接生成告警。
- 不要让客户逐字段维护映射作为默认交付流程。
- 不要把 demo 数据、mock 数据、临时代码混入正式链路。
- 修改数据库结构必须走 Flyway migration。
- 修改接口后同步检查前端类型和页面调用。
- 提交前至少跑：

```powershell
cd backend
mvn -pl edsp-core -am test
```

如果本次提交包含前端改动，再跑：

```powershell
cd frontend
npm run build
```
