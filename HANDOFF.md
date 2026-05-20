# EDSP Phase 1 骨架 — 交接文档

## 项目位置

`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前状态

**Phase 1 骨架** — 平台框架已搭建，可启动、可配置、可扩展，但所有业务逻辑都是占位实现。

### 已具备的能力

- 6 模块 Spring Cloud 微服务（Gateway / Auth / Core / Alert / Report / Common），通过 Nacos 注册发现
- PostgreSQL 初始表结构（Flyway V1 迁移，9 张表）
- **SQL Server 数据源连接器** — 测试连接、浏览数据库/表/字段、采样数据
- 数据源 / Schema 表与字段 / 字段映射 的 CRUD
- 规则 / 告警 CRUD + 状态流转（占位）
- 报表任务 CRUD + 空 Excel 模板导出（Apache POI）
- **总览仪表盘** — 风险态势、数据源健康、告警趋势、Schema 映射进度（真实数据驱动）
- React 管理台 6 个页面（总览 / 数据源 / Schema / 规则 / 告警 / 报表）
- **H2 本地开发 profile** — 无 Docker 也可启动全功能后端
- Docker Compose 一键启动全栈

### 明确不在此阶段范围

- 不接真实 SQL Server / IP-Guard 数据源
- 无 AI / 大模型 / 自学习
- 无 ClickHouse / Kafka / CDC / K8s
- 无真实事件采集 / 告警触发
- 无模拟数据

---

## 技术栈

| 层 | 技术 |
|---|---|
| 后端框架 | Java 21 / Spring Boot 3.3.5 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.1.2 |
| 注册配置 | Nacos 2.4.3 |
| 数据库 | PostgreSQL 16（Flyway 迁移）+ H2（本地无 Docker 开发） |
| 缓存 | Redis 7 |
| 前端 | React 18 / TypeScript / Ant Design 5 / Vite 5 |
| 构建 | Maven（后端）+ npm（前端） |
| 部署 | Docker Compose |

---

## 目录结构

```
backend/
  pom.xml                 # 父 POM，依赖版本管理
  edsp-common/            # ApiResponse<T> / PageResult<T> / OptionItem
  edsp-gateway/           # Spring Cloud Gateway，路由转发
  edsp-auth/              # 登录占位，/api/auth/**
  edsp-core/              # 数据源 + Schema CRUD + Flyway V1 迁移
    service/SqlServerMetadataService.java  # SQL Server 元数据扫描与连接测试
    controller/OverviewController.java     # /api/core/overview 总览聚合
  edsp-alert/             # 规则 + 告警 CRUD
  edsp-report/            # 报表任务 + Excel 导出
  */resources/
    application.yml         # 默认配置（Docker 环境）
    application-local.yml   # H2 本地开发覆盖（nacos.discovery.enabled=false）
  Dockerfile              # 多阶段构建：maven build → jre run
frontend/
  src/api.ts              # apiGet / apiPost 封装
  src/types.ts            # TS 类型定义（含 OverviewData）
  src/App.tsx             # 布局 + 导航 + 用户菜单
  src/pages/
    DashboardPage.tsx       # 总览仪表盘（环形图、趋势柱状图、实时指标）
    DataSourcesPage.tsx     # SQL Server 连接配置 + 测试 + 库表扫描 UI
    SchemaPage.tsx          # Schema 映射管理
    RulesPage.tsx           # 规则中心
    AlertsPage.tsx          # 告警中心
    ReportsPage.tsx         # 报表中心
  Dockerfile              # node build → nginx serve
  nginx.conf              # /api 代理到 gateway
docker-compose.yml
scripts/                  # PowerShell 本地开发脚本
```

---

## 本地启动

### Docker 方式

```powershell
Copy-Item .env.example .env
docker compose up --build
```

启动后：
- 前端：`http://localhost:3000`
- 网关：`http://localhost:8080`
- Nacos：`http://localhost:8848/nacos`

### 无 Docker 方式（H2 内存库）

```powershell
.\scripts\start-local-backend.ps1

cd frontend && npm install && npm run dev
# → http://localhost:5173
```

---

## 代码中的已知占位点

| 位置 | 占位内容 | 待替换为 |
|------|----------|----------|
| `AuthController.login()` | 不校验密码，Base64 假 token | Spring Security + JWT |
| `AuthController.me()` | 硬编码 admin 用户 | 数据库查用户 |
| `ReportController.emptyTemplate()` | 静态 2 行 Excel | 真实数据填充 |
| **所有 Controller** | **无任何鉴权** | 认证拦截器 |
| `DashboardPage` 告警 Badge | 硬编码 `count={12}` | 真实开放告警数 |

> 已修复：
> - ~~`DataSourceController.testConnection()` 固定返回 "not_configured"~~ → `SqlServerMetadataService.test()` 实现真实 JDBC 连接测试
> - ~~`DashboardPage` 指标卡片全为 0~~ → `OverviewController` + `/api/core/overview` 提供真实聚合数据

---

## 已知问题（两轮审查汇总）

### 审查时间线
- **第一轮**（Phase 1 骨架初建）：14 项
- **第二轮**（新增 SQL Server 连接器 + 总览仪表盘）：13 项

---

### 严重（5 项）⚠️ 需优先修复

| # | 轮次 | 问题 | 位置 |
|---|------|------|------|
| 1 | R1 | **登录不校验密码** — 任意用户名+密码获得 token，token 只是 Base64(username:timestamp)，无签名无过期 | `AuthController.login()` |
| 2 | R1 | **全平台无鉴权** — 所有 API 端点对外开放，Gateway 无认证 filter | 全部 Controller |
| 3 | R1 | **文档与代码技术栈不一致** — `PROJECT_BRIEF.md` 描述 Python FastAPI，实际 Java Spring Cloud | 项目文档 |
| 4 | **R2** | **SQL 注入** — `SqlServerMetadataService.sample()` 用字符串拼接构建 SQL，`quoteIdentifier()` 仅检测 `]` 字符，可绕过 | `SqlServerMetadataService.java:138` |
| 5 | **R2** | **未处理异常导致 500** — `DataSourceController.sourceRow()` 调用 `queryForMap`，ID 不存在时抛 `EmptyResultDataAccessException`，无 catch，影响 testConnection/tables/columns/sample 四个端点 | `DataSourceController.java:139-145` |

### 中等（7 项）

| # | 轮次 | 问题 | 位置 |
|---|------|------|------|
| 6 | R1 | 零测试文件（6 个 Maven 模块 + 前端） | 全项目 |
| 7 | R1 | 所有列表接口无分页（`PageResult` 类已定义但未使用） | 全部 Controller |
| 8 | R1 | 缺少关键数据库索引（`alerts.severity`、`alerts.status`、`rules.enabled` 等） | `V1__init.sql` |
| 9 | R1 | Redis 无持久化 volume | `docker-compose.yml` |
| 10 | R1 | 前端 submit 函数无 try/catch，API 失败用户无感知 | 部分页面 |
| 11 | **R2** | **密码存储与回流风险** — `config_json` 含明文密码存入数据库，通过 `sourceRow()` 读取后虽未直接返回 password 字段，但缺乏白名单过滤 | `SqlServerMetadataService` |
| 12 | **R2** | **INSERT 失败静默返回 id=0** — `DataSourceController.create()` 中 keyHolder 取不到 id 时返回 `{"id": 0, "message": "created"}`，调用方误以为成功 | `DataSourceController.java:60-61` |

### 低（12 项）

| # | 轮次 | 问题 | 位置 |
|---|------|------|------|
| 13 | R1 | `severityColor` 在 `RulesPage.tsx` 和 `AlertsPage.tsx` 中重复定义（DashboardPage 已统一但未复用） | 前端 |
| 14 | R1 | 前端 `api.ts` 缺少 `apiPut` / `apiDelete` | `api.ts` |
| 15 | R1 | Actuator health 端点公开 | 各 `application.yml` |
| 16 | R1 | `alert_notes` 表缺少 `updated_at` 字段 | `V1__init.sql` |
| 17 | ~~R1~~ | ~~`configJson`/`paramsJson` 硬编码~~ → **已修复**（`buildPayload()` 动态生成） | — |
| 18 | **R2** | `OverviewController.alertTrend()` 循环 7 次独立 SQL，不如一次 `GROUP BY date` | `OverviewController.java:191-203` |
| 19 | **R2** | `OverviewController.countBy()` 全表扫描 `alerts` 做 `group by lower(severity)`，无函数索引 | `OverviewController.java:205-215` |
| 20 | **R2** | `DataSourceController.columns()` 的 `database` 参数冗余（service 层未使用） | `DataSourceController.java:114-125` |
| 21 | **R2** | `AlertController.createAlert()` 用 `queryForObject(... returning id)`，与 `DataSourceController` 的 `GeneratedKeyHolder` 方式不一致 | `AlertController.java:38-45` |
| 22 | **R2** | CSS 中三处静态 `conic-gradient` 与 JS 动态生成的 `conicGradient()` 同时存在，数据源不同时视觉不一致 | `styles.css` |
| 23 | **R2** | H2 local profile 的 Flyway 兼容性 — `V1__init.sql` 中 `bigserial`、`references ... on delete cascade` 等语法依赖 H2 PostgreSQL 模式的完整度 | `application-local.yml` |
| 24 | **R2** | `App.tsx` 告警 Badge 硬编码 `count={12}`，不是真实数据 | `App.tsx:51` |

---

## SQL Server 连接器 API 速查

| 端点 | 方法 | 用途 |
|------|------|------|
| `/api/core/data-sources/test` | POST | 新建前测试连接（不保存） |
| `/api/core/data-sources/{id}/test` | POST | 已保存数据源的连接测试，回写 status |
| `/api/core/data-sources/{id}/tables?database=&keyword=&limit=` | GET | 扫描库下表列表 |
| `/api/core/data-sources/{id}/columns?database=&schema=&table=` | GET | 查看表的字段结构 |
| `/api/core/data-sources/{id}/sample?database=&schema=&table=&limit=` | GET | 采样表数据 |
| `/api/core/overview` | GET | 总览聚合（数据源/规则/告警/报表/Schema进度） |

### config_json 格式

```json
{
  "host": "172.16.34.134",
  "port": 1433,
  "database": "OCULAR3",
  "username": "ipguard_reader",
  "password": "***",
  "encrypt": false,
  "trustServerCertificate": true
}
```

---

## 下一步开发建议（按优先级）

**当前阶段已完成：** SQL Server 连接器 + 总览仪表盘。代码已从纯占位骨架演进到具备元数据浏览和平台级数据聚合。

1. **修复 SQL 注入** — `SqlServerMetadataService.sample()` 字符串拼接改为参数化查询（严重安全隐患）
2. **补 sourceRow 异常处理** — `DataSourceController` 中不存在的 ID 应返回 404 而非 500
3. **补认证鉴权** — 引入 Spring Security + JWT，所有后续开发依赖此基础
4. **同步文档技术栈** — README 顶部注明实际为 Java Spring Cloud（已与 Python 设计偏离）
5. **Flayway V2 迁移** — 补索引：`alerts(severity, status)`、`rules(enabled)`、`data_sources(status)`；补函数索引 `lower(severity)`
6. **规则引擎实现** — 基于真实告警字段实现规则表达式求值（替换占位）
7. **分页统一接入** — 列表接口接入 `PageResult`，前端加搜索/分页
8. **补充测试** — 至少覆盖 API 响应格式、Flyway 迁移、SQL Server 连接器
9. **基础设施加固** — Redis 加 volume、alert/report 服务加 postgres 健康检查依赖
10. **提取共享工具** — `severityColor`/`severityLabel`/`statusTag` 抽取到 `src/utils.tsx`
