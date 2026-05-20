# 数据安全预警分析平台

这是从零搭建的企业数据安全预警分析平台。当前目标不是绑定某个固定 SQL Server、IP-Guard 或 UEBA 数据库，而是形成可启动、可配置、可扩展、可复用的多源安全事件接入与告警运营平台。

## 技术底座

项目权威技术手册为 [TECHNICAL_MANUAL.md](./TECHNICAL_MANUAL.md)。

后续开发必须遵守技术手册中的产品定位、数据链路、菜单结构、接入方式、数据模型和安全约束。除非项目负责人明确要求调整，否则不得偏离以下主链路：

```text
外部系统 / 数据库 / API / Webhook / 文件 / Syslog
  -> 采集适配器
  -> raw_events / raw_logs / raw_imports
  -> 字段映射与标准化
  -> standard_events
  -> 规则 / 风险判断
  -> alerts
  -> 通知 / 处置 / 报表 / 反馈
```

## 技术栈

- 后端：Java 21、Spring Boot 3、Spring Cloud Alibaba、Nacos、Spring Cloud Gateway
- 数据库：PostgreSQL 16，Flyway 管理迁移
- 缓存：Redis 7
- 前端：React 18、TypeScript、Ant Design 5、Vite
- Python：后续 AI/分析服务预留，当前阶段不接入主链路

## 目录结构

```text
backend/
  edsp-common   公共响应模型
  edsp-gateway 统一网关
  edsp-auth    登录和用户信息占位
  edsp-core    数据源、Schema、字段映射、数据库迁移
  edsp-alert   规则和告警
  edsp-report  报表任务和空 Excel 模板
frontend/      React 管理台
docker-compose.yml
```

## 本地开发

安装 JDK 21、Maven、Node.js 后：

```powershell
cd backend
mvn clean package
```

```powershell
cd frontend
npm install
npm run dev
```

前端开发地址：

```text
http://localhost:5173
```

如果当前 PowerShell 没刷新到 Java/Maven PATH，可以直接用脚本：

```powershell
.\scripts\build-backend.ps1
.\scripts\build-frontend.ps1
.\scripts\dev-frontend.ps1
```

无 Docker 时，可以用 H2 本地库启动后端服务：

```powershell
.\scripts\start-local-backend.ps1
.\scripts\status-local-backend.ps1
```

停止本地后端：

```powershell
.\scripts\stop-local-backend.ps1
```

检查本机环境：

```powershell
.\scripts\check-env.ps1
```

## Docker Demo 启动

需要先安装 Docker。当前 `docker-compose.yml` 默认是演示部署模式，会自动生成 Demo 数据，不依赖真实客户数据库。

```powershell
Copy-Item .env.example .env
docker compose up --build
```

启动后访问：

```text
http://localhost:3000
```

如果前面接 1Panel / Lucky / Nginx，建议保持默认 3000，再把代理目标指向：

```text
http://127.0.0.1:3000
```

云服务器部署详见 [DEMO_DEPLOY.md](./DEMO_DEPLOY.md)。

## 当前已实现

- Spring Cloud 多模块后端骨架
- PostgreSQL 初始表结构
- H2 本地开发库启动链路
- 数据源管理 CRUD
- SQL Server 数据源配置和连接测试
- 标准告警接入 API：`POST /api/ingest/alerts`、`POST /api/ingest/alerts/batch`
- 告警幂等写入：同一来源系统和外部 ID 再次上报时更新原告警
- 通知中心基础链路：通知通道配置、Webhook 测试发送、通知发送记录
- 元数据快照页面：数据源下的二级菜单，承载库表字段扫描、字段映射、采集建议
- 外部接入页面：数据源下的二级菜单，承载数据库/API/Webhook 等外部系统接入
- 采集任务页面：数据源下的二级菜单，承载采集策略、运行状态、失败重试
- Schema 表和字段映射 API
- 规则、告警、报表任务 API
- 空 Excel 报表模板导出
- React + Ant Design 管理台

## 当前暂不做

- 不固定任何厂商、SQL Server 地址、账号、库名或字段
- 不把平台设计成只为 IPGUARD 服务
- 不让外部数据绕过 raw 层、standard_events 层，直接进入 alerts
- 不把客户一张表一张表确认、一列一列填写字段映射作为默认交付体验
- 不把规则中心设计成只能手写表达式
- 不接 AI、大模型、自学习训练
- 不接 ClickHouse、Kafka、CDC、K8s
- 不把 UEBA 或其他外部系统内部读取过程放到前台主流程展示
