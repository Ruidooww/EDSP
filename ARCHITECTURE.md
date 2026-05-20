# 数据安全预警分析平台技术架构

> 权威基线：本文件是架构摘要，完整约束以 [TECHNICAL_MANUAL.md](./TECHNICAL_MANUAL.md) 为准。  
> 旧的 IP-Guard UEBA 专项方案、Python FastAPI 主后端方案不再作为当前项目架构依据。

---

## 1. 架构目标

平台定位为多源安全事件接入与智能告警运营平台，不是单一数据库读取工具，也不是只服务 IP-Guard 的专项适配器。

核心目标：

1. 接入不同类型外部系统：数据库、API、Webhook、Syslog、文件、Agent、消息队列、日志平台。
2. 保留原始数据证据，支持回溯、重算、审计和模型训练。
3. 将不同来源事件统一归一化为标准事件，再进入规则、告警、通知和报表链路。
4. 降低客户配置成本，避免客户逐表逐字段手工配置。
5. 形成可复用的适配器、字段模板、规则模板和采集任务体系。

---

## 2. 当前技术栈

| 层级 | 当前选型 | 说明 |
|---|---|---|
| 后端 | Java 21 / Spring Boot 3 / Spring Cloud | 当前主后端技术栈 |
| 网关 | Spring Cloud Gateway | 统一 API 入口 |
| 注册配置 | Nacos | Demo 和微服务注册发现 |
| 数据库 | PostgreSQL 16 | 配置库、业务库、Demo 数据 |
| 迁移 | Flyway | 数据库结构版本管理 |
| 本地开发 | H2 PostgreSQL 模式 | 无 Docker 时本地开发 |
| 缓存 | Redis 7 | 缓存、计数、轻量队列预留 |
| 前端 | React 18 / TypeScript / Ant Design 5 / Vite | 管理台 |
| 部署 | Docker Compose | Demo 和小规模部署 |
| AI | Python 预留 | 后续模型服务，不替代 Java 主后端 |

---

## 3. 后端模块

```text
backend/
  edsp-common   公共响应模型、分页模型、通用 DTO
  edsp-gateway 统一网关，负责 API 路由
  edsp-auth    登录、账号信息、后续鉴权能力
  edsp-core    数据源、元数据、字段映射、审计、总览
  edsp-alert   告警、规则、通知、采集接入
  edsp-report  报表任务、报表导出
```

当前阶段先保持模块边界清晰，不提前引入 ClickHouse、Kafka、CDC、K8s 或本地大模型作为主链路依赖。

---

## 4. 目标数据链路

所有外部来源必须进入统一链路：

```text
external sources
  -> collection adapters
  -> raw_events / raw_logs / raw_imports
  -> parser / mapper / normalizer
  -> standard_events
  -> rules / risk decisions
  -> alerts
  -> notification / disposition / report / feedback
```

关键约束：

1. 外部数据不得绕过 raw 层直接进入告警层。
2. `standard_events` 是平台统一事件模型，不同产品字段必须归一化到这里。
3. `alerts` 保存平台研判结果，不替代原始证据。
4. 告警、通知、报表、模型学习都应基于标准事件和处置反馈闭环。

---

## 5. 核心数据域

### 5.1 数据源与适配器

```text
data_sources
collector_adapters
collection_tasks
ingestion_runs
ingestion_cursors
```

职责：

- 保存外部系统连接配置。
- 管理数据库、API、Webhook、文件、Syslog 等采集适配器。
- 管理采集任务、调度策略、游标和运行记录。

### 5.2 原始数据层

```text
raw_events
raw_logs
raw_imports
```

职责：

- 保存外部来源原始 payload。
- 支持证据回溯、重新映射、规则重跑、适配器排错。

### 5.3 元数据与映射

```text
schema_scan_runs
schema_tables
schema_fields
field_mappings
ingestion_plans
template_matches
```

职责：

- 扫描数据库、文件、API 返回结构。
- 识别表、字段、样本、主键、时间字段、候选事件表。
- 自动匹配字段模板并生成采集计划。
- 人工确认只作为例外兜底，而不是默认交付路径。

### 5.4 标准事件与告警

```text
standard_events
alert_rules
alert_decisions
alerts
alert_notes
alert_assignments
feedback_labels
```

职责：

- 归一化不同来源事件。
- 执行业务规则、风险评分和告警生成。
- 支持告警处置、标注反馈和后续模型学习。

### 5.5 通知与报表

```text
notification_channels
notification_deliveries
report_jobs
audit_logs
```

职责：

- 对接 Webhook、企业微信、飞书、短信、邮件等通知通道。
- 记录通知投递结果。
- 生成周期报表、处置报表、审计报表。
- 记录平台关键操作审计。

---

## 6. 外部系统接入方式

优先级：

| 优先级 | 接入方式 | 说明 |
|---|---|---|
| P1 | 数据库只读连接 | 适合本地化部署产品，支持心跳、元数据扫描、增量采集 |
| P1 | REST API 拉取 | 适合开放 API 的安全产品 |
| P1 | Webhook 推送 | 适合外部系统主动推送告警 |
| P2 | 文件导入 | CSV、JSON、XML、日志文件离线导入 |
| P2 | Syslog | 防火墙、网关、网络设备、安全设备日志 |
| P3 | Agent / CLI | 无 API、无库权限时的兜底方式 |
| P3 | MQ / 日志平台 | 对接 Kafka、Redis Stream、ELK、Loki 等后续扩展 |

每种接入方式都必须落到统一的 raw 层和标准事件层。

---

## 7. 数据库智能识别

连接数据库后，平台不应要求客户手工逐表逐字段录入。应通过以下能力自动完成大部分工作：

1. Database Inventory：枚举数据库、Schema、表、视图、字段、索引、行数。
2. Coverage Verification：核验是否扫描完整，识别被权限、网络、超时跳过的库表。
3. Semantic Profiler：基于表名、字段名、注释、样本推断业务语义。
4. Template Matcher：匹配已知产品模板或通用安全事件模板。
5. Ingestion Plan Generator：生成候选采集计划和字段映射。
6. Shadow Validator：试运行采集，不直接进入告警生产链路。

新增字段处理原则：

- 默认不阻断采集。
- 新字段先进入元数据变更记录和 raw payload。
- 高置信度字段可自动归一化到 `standard_events.extra` 或对应标准字段。
- 低置信度字段进入待确认队列，但不要求客户日常逐字段维护。
- 有业务风险的字段变更才触发通知。

---

## 8. 前端信息架构

当前菜单基线：

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

页面职责：

- 数据源管理：管理外部系统连接、状态、凭据、安全配置。
- 元数据快照：扫描结果、字段识别、字段映射、采集建议。
- 外部接入：数据库、API、Webhook、文件、Syslog 等接入配置。
- 采集任务：调度、运行状态、失败重试、游标、采集统计。
- 规则中心：面向业务场景的规则模板和参数化配置，不以手写表达式为默认体验。
- 告警中心：告警列表、处置、分派、标注、证据查看。
- 通知中心：通知通道、投递记录、失败重试。
- 报表：演示数据、统计报表、任务状态、导出记录。
- 设置：账号、角色、审计、安全配置。

---

## 9. 安全基线

正式部署必须遵守：

1. 外部系统账号、密码、Token、Webhook 密钥不得明文存储。
2. 凭据字段不得在列表、详情、日志、错误信息中回显。
3. 数据源推荐使用只读账号和最小权限。
4. 所有关键操作写入 `audit_logs`。
5. 对外 API 必须有鉴权、限流和审计。
6. Webhook 和 API 接入必须支持签名校验或密钥校验。
7. Demo 数据与真实客户数据必须隔离。

---

## 10. 阶段路线

### P0：技术底座固化

- 技术手册作为权威入口。
- 菜单结构、数据链路、数据模型统一。
- 修复旧文档中的 IP-Guard/FastAPI 偏差。

### P1：数据源与元数据

- 完善数据源管理。
- 完善数据库扫描完整性核验。
- 元数据快照支持字段识别、模板匹配、映射建议。

### P2：采集链路

- 建立 raw 层、标准事件层、采集任务、采集运行记录。
- 支持数据库、API、Webhook 的最小可用采集。

### P3：规则与告警

- 规则中心改为业务模板和参数化配置。
- 基于 `standard_events` 生成告警。
- 告警中心支持处置、分派、标注反馈。

### P4：通知与报表

- 完成 Webhook、企业微信、飞书、短信、邮件适配。
- 报表页面补齐演示数据、任务状态、导出结果。

### P5：模型与学习闭环

- 基于长期标准事件和人工反馈再接入 AI。
- Python 模型服务作为独立服务接入，不替代 Java 主后端。
