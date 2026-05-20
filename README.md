# EDSP 企业数据安全分析平台

这是从零搭建的 EDSP 第一阶段工程骨架。当前目标不是绑定某个固定 SQL Server 或真实数据源，而是先形成可启动、可配置、可扩展的企业级平台底座。

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
- Schema 表和字段映射占位 API
- 规则、告警、报表任务占位 API
- 空 Excel 报表模板导出
- React + Ant Design 管理台

## 当前暂不做

- 不固定任何厂商、SQL Server 地址、账号、库名或字段
- 不把平台设计成只为 IPGUARD 服务
- 数据库网络不可达时，不继续推进库表扫描
- 不接 AI、大模型、自学习训练
- 不接 ClickHouse、Kafka、CDC、K8s
- 不把 UEBA 或其他外部系统内部读取过程放到前台主流程展示
