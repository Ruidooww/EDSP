# EDSP — 数据安全预警分析平台

## 项目概述

EDSP（Enterprise Data Security Platform）是一个**多源安全事件接入与智能告警运营平台**，不绑定任何单一厂商或数据库，支持多种外部系统的事件接入、标准化、规则判定、告警处置与报表输出。

- 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`
- Git 仓库：`git@github.com:Ruidooww/EDSP.git`
- 当前阶段：Phase 1 技术底座（框架完整可运行，部分业务逻辑为占位实现）

---

## 技术栈

| 层 | 技术 |
|---|---|
| 后端框架 | Java 21 / Spring Boot 3.3.5 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.1.2 |
| 注册配置 | Nacos 2.4.3（Docker 下禁用，直连服务） |
| 数据库 | PostgreSQL 16 + Flyway 迁移（本地开发支持 H2） |
| 缓存 | Redis 7 |
| 前端 | React 18 / TypeScript / Ant Design 5 / Vite 6 |
| 构建 | Maven（后端）+ npm（前端） |
| 部署 | Docker Compose |

---

## 后端架构

### 模块划分

| 模块 | 端口 | 职责 |
|------|------|------|
| edsp-common | — | 通用库：ApiResponse<T>、PageResult<T>、OptionItem |
| edsp-gateway | 8080 | Spring Cloud Gateway 路由分发 |
| edsp-auth | 8081 | 认证服务（当前 Demo 阶段，Base64 token） |
| edsp-core | 8082 | 核心服务：数据源管理、Schema 元数据、事件管线、总览仪表盘 |
| edsp-alert | 8083 | 告警摄入、规则引擎、通知调度、SQL Server OME 同步 |
| edsp-report | 8084 | 报表生成（Apache POI Excel 导出） |

### 启动顺序

```
postgres → (auth, core) → (alert, report) → gateway → frontend
```

### API 路由（通过 Gateway）

| 路径前缀 | 目标服务 |
|----------|----------|
| /api/auth/** | edsp-auth:8081 |
| /api/core/** | edsp-core:8082 |
| /api/alerts/** | edsp-alert:8083 |
| /api/ingest/** | edsp-alert:8083 |
| /api/notifications/** | edsp-alert:8083 |
| /api/reports/** | edsp-report:8084 |

---

## 核心数据链路

```
外部系统事件
  → 采集适配器（数据库/API/Webhook/文件/Syslog/Agent）
    → raw_events / raw_logs / raw_imports（原始层）
      → 字段映射 & 标准化
        → standard_events（标准事件层）
          → 规则引擎判定（事件类型 × 时间窗口 × 阈值 × 范围）
            → alerts（告警幂等入库，按 sourceSystem+externalId 去重）
              → 通知推送（Webhook/企微/飞书/短信/邮件）
              → 处置流（open → processing → resolved → closed）
              → 报表输出
```

### 关键数据表

- **采集层**：data_sources、collector_adapters、collection_tasks、ingestion_runs、ingestion_cursors
- **原始层**：raw_events、raw_logs、raw_imports
- **标准层**：standard_events（dedup_key 去重）
- **元数据层**：schema_tables、schema_fields、field_mappings、schema_scan_runs、template_matches
- **业务层**：rules、alerts、alert_notes、alert_decisions、feedback_labels
- **通知层**：notification_channels、notification_deliveries
- **审计层**：audit_logs
- **报表层**：report_jobs

---

## 前端架构

### 页面清单

| 页面 | 组件 | 功能 |
|------|------|------|
| DashboardPage | 总览仪表盘 | 风险态势、数据源健康、告警趋势、Schema 进度 |
| DataSourcesPage | 数据源管理 | 8 种预置模板、连接测试、库表字段扫描 |
| SchemaPage | Schema 元数据 | 快照管理、字段映射、扫描运行记录 |
| AlertSyncPage | 外部接入 | SQL Server OME 同步、心跳检测、告警采集 |
| CollectionTasksPage | 采集任务 | 任务编排、运行记录、raw/standard 事件查看 |
| AlertsPage | 告警中心 | 告警列表、处置流、采样推送 |
| RulesPage | 规则中心 | 5 种预置场景、模板化创建、启用/禁用 |
| NotificationsPage | 通知中心 | 5 种渠道管理、投递记录 |
| ReportsPage | 报表中心 | 4 种模板、Excel 导出 |
| SettingsPage | 系统设置 | 运行时参数（当前占位） |

### 通用组件

- `EmptyPanel` — 空状态占位
- `api.ts` — HTTP 客户端（apiGet/apiPost/apiPut，Bearer token 认证）
- `types.ts` — 全局 TypeScript 类型定义
- `styles.css` — 全局样式（响应式断点：1200px / 900px / 640px）

---

## 关键 API 端点

### 外部告警摄入

```
POST /api/ingest/alerts        # 单条摄入
POST /api/ingest/alerts/batch  # 批量摄入
```

请求体（IngestAlertRequest）：

```json
{
  "sourceSystem": "terminal-security",
  "externalId": "uuid-12345",
  "alertType": "file_leakage",
  "title": "疑似敏感文件外发",
  "severity": "high",
  "occurredAt": "2026-05-20T10:30:00+08:00",
  "actor": "zhangsan",
  "asset": "研发服务器-01",
  "policyName": "DLP-文件外发策略",
  "detail": { "fileSize": "15MB", "fileType": "docx" }
}
```

### SQL Server 连接器

| 端点 | 用途 |
|------|------|
| POST /api/core/data-sources/{id}/test | 连接测试 |
| GET /api/core/data-sources/{id}/tables | 浏览数据库表 |
| GET /api/core/data-sources/{id}/columns | 查看表字段结构 |
| GET /api/core/data-sources/{id}/sample | 采样表数据 |
| POST /api/ingest/sqlserver/omen/sync | OME 告警同步 |

### 告警管理

| 端点 | 用途 |
|------|------|
| GET /api/alerts | 告警列表 |
| PUT /api/alerts/{id}/status | 更新状态 |
| GET /api/alerts/{id}/notes | 查看处置记录 |
| POST /api/alerts/{id}/notes | 添加处置备注 |
| GET /api/alerts/rules | 规则列表 |
| POST /api/alerts/rules | 创建规则 |

### 通知管理

| 端点 | 用途 |
|------|------|
| GET /api/notifications/channels | 渠道列表 |
| POST /api/notifications/channels | 创建渠道 |
| POST /api/notifications/channels/{id}/test | 测试渠道 |
| POST /api/notifications/send | 发送通知 |

---

## 本地开发

### Docker 方式

```powershell
Copy-Item .env.example .env
docker compose up --build
```

- 前端：`http://localhost:18080`（由 FRONTEND_PORT 控制）
- 网关：`http://localhost:8080`

### 无 Docker 方式（H2 内存库）

```powershell
.\scripts\start-local-backend.ps1   # 启动 5 个后端服务
cd frontend && npm install && npm run dev  # 启动前端 → http://localhost:5173
```

### 常用脚本

| 脚本 | 用途 |
|------|------|
| build-backend.ps1 | Maven 构建全部后端模块 |
| build-frontend.ps1 | npm 构建前端 |
| start-local-backend.ps1 | 本地启动全部后端 |
| stop-local-backend.ps1 | 停止全部后端进程 |
| status-local-backend.ps1 | 查看后端端口状态 |
| dev-frontend.ps1 | 启动前端开发服务器 |
| deploy-demo.sh | Docker Compose 一键部署 |
| check-env.ps1 | 检查开发环境工具链版本 |

---

## 已知问题

### 严重（需优先修复）

1. **无认证鉴权** — 登录不校验密码，所有 API 端点对外开放
2. **SQL 注入** — `SqlServerMetadataService.sample()` 使用字符串拼接 SQL
3. **未处理异常** — `DataSourceController` 中 ID 不存在时抛出 500
4. **文档不一致** — PROJECT_BRIEF.md 描述 Python FastAPI，实际为 Java Spring Cloud

### 中等

- 零测试覆盖
- 列表接口无分页（PageResult 已定义但未使用）
- 缺少关键数据库索引
- Redis 无持久化 volume
- 明文密码存储于 config_json
- INSERT 失败静默返回 id=0

### 低优先级

- 前端重复代码（severityColor 多处定义）
- api.ts 缺少 apiDelete
- Actuator 端点公开
- H2 PostgreSQL 兼容性边缘情况
- 部分 SQL 查询效率低

---

## 下一步开发建议（按优先级）

1. 引入 Spring Security + JWT 认证鉴权
2. 修复 SQL 注入，改为参数化查询
3. 补全异常处理，统一错误响应
4. 建立 raw_events → standard_events 标准化管线
5. 实现规则引擎真实求值
6. 数据库索引优化
7. 列表接口接入分页
8. 补充测试覆盖
9. 提取前端共享工具函数
