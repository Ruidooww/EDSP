# EDSP 技术手册

> 版本：v1.0  
> 日期：2026-05-20  
> 状态：项目技术底座基线  
> 约束：后续开发必须遵守本手册。除非项目负责人明确要求调整，否则不得偏离本手册定义的产品定位、数据链路和架构边界。

---

## 1. 项目定位

EDSP 是企业数据安全预警分析平台，不是单一数据库读取工具，也不是只服务 IP-Guard 的专项适配器。

平台目标是把数据库、API、Webhook、Syslog、文件、Agent、消息队列、日志平台等不同来源的安全事件统一接入，完成原始数据留存、标准事件归一化、规则分析、风险评分、告警处置、通知推送、报表输出和后续学习闭环。

一句话定位：

```text
多源安全事件接入与智能告警运营平台。
```

核心判断：

```text
EDSP 的核心不是一开始就知道所有厂家的标准答案，
而是具备发现、核验、试运行、修正、学习和复用的能力。
```

未知数据源第一次接入要可控，第二次接入要可复用，第三次接入要逐步标准化。

---

## 2. 不可偏离原则

后续功能开发必须遵守以下原则：

1. 不把 EDSP 设计成只服务 IP-Guard、UEBA 或某一个固定厂商的平台。
2. 不让外部数据直接绕过原始层进入告警层。
3. 不让客户一张表一张表确认、一列一列配置字段映射作为默认交付体验。
4. 不把规则中心设计成只让客户手写表达式。
5. 不把 AI 或本地大模型作为当前主链路前置依赖。
6. 不在没有扫描完整性核验的情况下宣称数据库识别完成。
7. 不把外部系统内部读取细节放成客户日常主流程。
8. 不在正式交付中明文保存数据库账号密码、Webhook 密钥或 API Token。
9. 不让旧的 IP-Guard 专项方案覆盖当前多源平台方向。
10. 不为了 Demo 快速展示破坏 `raw -> standard -> alerts` 主链路。

---

## 3. 当前技术栈基线

当前项目采用 Java 后端和 React 前端：

| 层级 | 当前选型 | 说明 |
|---|---|---|
| 后端 | Java 21 / Spring Boot 3 / Spring Cloud | 当前主后端技术栈 |
| 网关 | Spring Cloud Gateway | 统一 API 入口 |
| 数据库 | PostgreSQL 16 / Flyway | 正式环境配置库与业务库 |
| 本地开发 | H2 PostgreSQL 模式 | 无 Docker 时本地启动 |
| 缓存 | Redis 7 | 缓存、计数、轻量队列预留 |
| 前端 | React 18 / TypeScript / Ant Design 5 / Vite | 管理台 |
| 部署 | Docker Compose | Demo 和小规模部署 |
| AI 服务 | Python 预留 | 后续模型服务，不替代主后端 |

当前后端模块：

```text
backend/
  edsp-common   公共响应模型
  edsp-gateway 统一网关
  edsp-auth    登录和账号信息
  edsp-core    数据源、元数据、审计、总览
  edsp-alert   告警、规则、通知、采集接入
  edsp-report  报表任务
```

---

## 4. 目标数据链路

所有来源必须统一进入以下链路：

```text
external sources
  ↓
collection adapters
  ↓
raw_events / raw_logs / raw_imports
  ↓
parser / mapper / normalizer
  ↓
standard_events
  ↓
rules / risk decisions
  ↓
alerts
  ↓
notification / disposition / report / feedback
```

### 4.1 raw 层

raw 层保存外部来源原始数据，不能只保存转换后的告警。

目的：

```text
1. 可回溯原始证据
2. 可重新映射字段
3. 可重新跑规则
4. 可训练模型和沉淀样本
5. 可定位适配器错误
```

建议表：

```text
raw_events
raw_logs
raw_imports
raw_payload_attachments
```

### 4.2 standard_events 层

standard_events 是 EDSP 内部统一事件模型。不同来源字段必须先归一化到这里，再由规则或模型判断是否生成告警。

最低标准字段：

| 字段 | 说明 |
|---|---|
| `source_system` | 来源系统 |
| `source_type` | 来源类型：database/api/webhook/file/syslog/agent |
| `external_id` | 外部事件 ID，用于幂等 |
| `event_type` | 标准事件类型 |
| `title` | 事件标题 |
| `severity_hint` | 来源系统原始等级或建议等级 |
| `occurred_at` | 事件发生时间 |
| `actor` | 操作人、账号、主体 |
| `asset_ref` | 终端、服务器、数据库、系统等资产 |
| `object_type` | file/table/api/device/user 等 |
| `object_ref` | 对象标识 |
| `policy_name` | 来源策略或规则 |
| `detail_json` | 标准化扩展详情 |
| `raw_event_id` | 原始数据引用 |

### 4.3 alerts 层

alerts 只保存需要关注、处置、通知或统计的告警，不保存所有原始事件。

告警状态建议：

```text
open            新告警
assigned        已分派
processing      处理中
confirmed       已确认风险
false_positive  误报
resolved        已处置
closed          已关闭
archived        自动归档
```

告警处理分层：

| 判定结果 | 处理方式 |
|---|---|
| `immediate_alert` | 立即告警并通知 |
| `normal_alert` | 进入告警中心 |
| `low_priority` | 低优先级汇总 |
| `archive_only` | 仅记录标准事件，不生成告警 |

---

## 5. 核心数据模型

后续数据库设计应围绕以下核心实体展开。

### 5.1 数据源与采集

```text
data_sources            数据源配置
collector_adapters      采集适配器定义
collection_tasks        采集任务
ingestion_runs          每次采集执行记录
ingestion_cursors       增量游标
raw_events              原始事件
raw_logs                原始日志
raw_imports             文件导入记录
```

### 5.2 元数据识别

```text
schema_scan_runs        扫描会话
schema_tables           表/文件/接口对象快照
schema_fields           字段快照
field_mappings          来源字段到标准字段映射
ingestion_plans         系统生成的接入方案
template_matches        模板匹配结果
```

### 5.3 事件、告警、处置

```text
standard_events         标准事件
alert_rules             告警规则
alert_decisions         规则/模型判定记录
alerts                  告警
alert_notes             处置备注
alert_assignments       分派记录
feedback_labels         人工反馈和学习标签
```

### 5.4 通知、报表、审计

```text
notification_channels   通知通道
notification_deliveries 通知发送记录
report_jobs             报表任务
audit_logs              操作审计
```

---

## 6. 多源接入体系

EDSP 支持多种接入方式，但近期优先级必须收敛。

### 6.1 支持范围

| 类型 | 说明 | 优先级 |
|---|---|---|
| 数据库接入 | SQL Server、MySQL、PostgreSQL、Oracle 等 | P1 |
| HTTP API | 外部系统开放 API，平台定时拉取 | P1 |
| Webhook | 外部系统主动推送事件 | P1 |
| 文件导入 | CSV、Excel、JSON、XML | P2 |
| Syslog | UDP/TCP/TLS、CEF、LEEF、自定义文本 | P2 |
| Agent / Collector | 无接口、无数据库权限时本地采集 | P3 |
| 消息队列 | Kafka、RabbitMQ、Redis Stream | P3 |
| 日志平台/SIEM | 第三方日志平台二次接入 | P3 |

### 6.2 数据库接入原则

数据库接入不是只扫表名，而是形成数据库画像：

```text
catalog
schema
table
column
index
primary_key
foreign_key
row_count
sample_values
last_update_time
permission_scope
```

数据库接入流程：

```text
连接测试
  ↓
权限探测
  ↓
全量 inventory 扫描
  ↓
扫描完整性核验
  ↓
表角色识别
  ↓
字段语义识别
  ↓
接入方案生成
  ↓
试运行沙箱
  ↓
正式采集任务
```

### 6.3 API / Webhook 接入原则

API 和 Webhook 不能只做简单转发，必须经过：

```text
签名/Token 校验
  ↓
原始 payload 入 raw_events
  ↓
字段标准化
  ↓
幂等去重
  ↓
规则判定
```

### 6.4 文件导入原则

文件导入用于历史数据、离线数据和客户演示数据。

要求：

```text
1. 支持 CSV、Excel、JSON
2. 自动识别表头和字段类型
3. 自动推荐字段映射
4. 导入前预览
5. 导入后进入 raw_imports 和 standard_events
```

### 6.5 Syslog 原则

Syslog 是正式产品必须支持的一等接入方式，因为很多安全设备、网关、一体机不开放数据库或 API。

支持目标：

```text
UDP Syslog
TCP Syslog
TLS Syslog
CEF
LEEF
key=value
JSON line
自定义正则 parser
```

---

## 7. 数据库智能识别引擎

数据库智能识别引擎的目标不是立即准确识别所有厂商，而是构建可核验、可修正、可复用的识别闭环。

### 7.1 六个核心模块

```text
1. Database Inventory
   数据库完整画像采集

2. Coverage Verification
   扫描完整性核验

3. Semantic Profiler
   表/字段语义画像

4. Template Matcher
   语义模板匹配

5. Ingestion Plan Generator
   接入方案生成器

6. Shadow Validator
   试运行验证器
```

### 7.2 表角色识别

表角色分类不依赖单一表名，应综合以下信息：

```text
表名
字段名
字段类型
字段注释
行数
更新时间
索引
主键
外键
样本值
与其他表的关系
```

推荐表角色：

```text
alert_table        告警表
event_log          事件日志表
operation_log      操作日志表
user_table         用户/账号表
asset_table        资产/终端表
policy_table       策略/规则表
dictionary_table   字典表
system_table       系统配置表
unknown            未知
```

### 7.3 字段语义槽位

模板不能写死字段名，必须识别字段表达的语义槽位。

标准槽位：

```text
externalId
title
severity
eventType
occurredAt
actor
asset
objectType
objectRef
policyName
status
detail
rawRecordId
```

字段语义识别依据：

```text
字段名
字段类型
样本值
唯一性
空值率
数据分布
是否像时间
是否像等级
是否像用户名
是否像 IP / MAC / 主机名
是否像文件路径
是否像手机号 / 邮箱 / 身份证 / 银行卡
是否与其他表存在关系
```

### 7.4 接入方案

系统识别后应生成接入方案，而不是直接要求客户确认字段。

接入方案示例：

```json
{
  "dataSourceId": 1,
  "planName": "通用 DLP 告警接入方案",
  "mode": "alert_table",
  "confidence": 92,
  "mainTable": "T_RISK_EVENT",
  "joinTables": ["T_RISK_DETAIL", "T_USER_INFO"],
  "cursorField": "CREATE_TIME",
  "idField": "ID",
  "fieldMappings": {
    "ID": "externalId",
    "RISK_NAME": "title",
    "RISK_LEVEL": "severity",
    "CREATE_TIME": "occurredAt",
    "USER_ACCOUNT": "actor",
    "HOST_NAME": "asset"
  },
  "syncStrategy": {
    "type": "incremental",
    "cursor": "CREATE_TIME",
    "interval": "5m"
  },
  "risk": [
    "RISK_LEVEL 样本较少，建议试运行观察",
    "未发现处置状态字段，默认状态 open"
  ]
}
```

---

## 8. 扫描完整性核验

数据库扫描必须有核验结果，不能只显示“扫描完成”。

### 8.1 核验指标

```text
数据库实际表数
扫描发现表数
成功分析表数
失败表数
忽略表数
字段采集数量
样本采集数量
覆盖率
失败原因
核验状态
```

### 8.2 schema_scan_runs 建议结构

```sql
create table if not exists schema_scan_runs (
    id bigserial primary key,
    data_source_id bigint not null,
    scan_type varchar(40) not null default 'full',
    status varchar(40) not null default 'running',

    expected_table_count int,
    discovered_table_count int,
    analyzed_table_count int,
    failed_table_count int,
    ignored_table_count int,

    coverage_rate numeric(5,2),
    verification_status varchar(40),

    started_at timestamptz not null default now(),
    finished_at timestamptz,
    error_message text
);
```

### 8.3 核验类型

```text
1. 总量核验：实际表数与扫描表数是否一致
2. 分页核验：分页扫描是否漏页
3. 权限核验：是否因权限不足漏扫
4. Schema 范围核验：是否只扫了 dbo/public 等部分 schema
5. 表结构快照核验：字段数、主键、索引是否完整
```

---

## 9. 规则与告警

### 9.1 规则中心原则

客户不应该被要求填写底层字段名或复杂表达式。

错误体验：

```text
event_type = file_operation
file_size > 104857600 && after_hours == true
```

正确体验：

```text
选择事件类型：文件外发 / U 盘拷贝 / 邮件外发 / 异常下载
选择触发条件：超过 N 个文件 / 超过 N MB / 非工作时间 / 外部收件人
选择等级：高 / 中 / 低
选择通知策略：立即通知 / 汇总通知 / 只入库
```

底层仍可生成结构化规则表达式，但默认不暴露给客户。

### 9.2 规则执行顺序

```text
standard_events
  ↓
规则模板匹配
  ↓
风险评分
  ↓
去重和聚合
  ↓
告警生成或归档
```

### 9.3 处置闭环

告警中心必须支持：

```text
状态流转
负责人
处置备注
通知记录
原始事件引用
关联事件
误报标记
处置结果回流
```

---

## 10. 客户交付体验

客户默认使用托管模式，实施人员和高级管理员使用专家模式。

| 模式 | 面向对象 | 展示内容 |
|---|---|---|
| 托管模式 | 客户安全管理员 | 风险、建议、确认按钮、处置任务 |
| 专家模式 | 实施工程师/高级管理员 | 表、字段、映射、扫描日志、接入方案 |

客户应确认业务策略，而不是确认字段映射。

客户应该确认：

```text
是否启用文件外发告警
是否启用 U 盘拷贝告警
是否启用邮件外发告警
是否启用非工作时间操作告警
是否高危立即通知
```

客户不应该确认：

```text
alarm_time -> occurredAt
user_name -> actor
risk_level -> severity
```

---

## 11. 前端信息架构

当前前端菜单基线：

```text
总览
数据源
  数据源管理
  元数据快照
  外部接入
  采集任务
告警中心
规则中心
通知中心
报表
设置
```

### 11.1 页面职责

| 页面 | 职责 |
|---|---|
| 总览 | 展示平台运营状态、风险趋势、待处理事项 |
| 数据源管理 | 管理数据库、API、Webhook、文件、安全平台等来源 |
| 元数据快照 | 展示扫描结果、字段识别、字段映射、变更情况 |
| 外部接入 | 展示外部系统心跳、采集链路、最近标准告警 |
| 采集任务 | 展示任务调度、游标、执行记录、失败重试 |
| 告警中心 | 告警查看、分派、处置、关闭、误报反馈 |
| 规则中心 | 业务规则模板、启停、等级和通知策略 |
| 通知中心 | Webhook、企微、飞书、短信、邮件等通道 |
| 报表 | 风险汇总、处置统计、审计材料 |
| 设置 | 平台参数、审计、安全、运行配置 |

---

## 12. 安全底线

当前 Demo 可以简化安全，但正式产品必须满足：

```text
1. Spring Security + JWT 或等价认证
2. Gateway 统一鉴权
3. 数据源凭据加密存储
4. API Token / Webhook Secret 加密存储
5. Webhook 目标和外部 API 地址做 SSRF 防护
6. 数据库采样接口限制行数和权限
7. 所有关键操作写 audit_logs
8. 导出报表和原始数据需要权限控制
9. 默认账号密码不得用于正式部署
10. 接入源建议使用只读账号和最小权限
```

---

## 13. AI 与模型路线

AI 是增强能力，不是当前主链路前提。

实施顺序：

```text
第一阶段：规则 + 模板 + 启发式评分
第二阶段：保存人工反馈样本
第三阶段：结构化风险评分模型
第四阶段：本地小语言模型做解释和语义辅助
第五阶段：告警降噪和优先级排序
```

### 13.1 两类模型

| 类型 | 代表 | 职责 |
|---|---|---|
| 结构化模型 | XGBoost、LightGBM、Isolation Forest | 风险评分、异常检测、误报预测 |
| 本地小语言模型 | 0.7B/1B/7B 级模型 | 字段语义、表角色解释、扫描报告、处置建议 |

不建议从零训练模型。后续如做模型，应先积累以下数据：

```text
字段语义识别样本
表类型识别样本
扫描核验样本
人工修正记录
模板匹配结果
告警处置结果
误报/真风险标签
```

---

## 14. 实施路线

### P0 当前 Demo/POC

目标：

```text
平台可启动
菜单结构合理
演示数据完整
数据源、告警、规则、通知、报表可展示
```

### P1 统一中间层

必须优先完成：

```text
1. raw_events / raw_logs / raw_imports
2. standard_events
3. alerts 只保存需要关注的告警
4. ingestion_runs 和 collection_tasks
5. 所有来源先 raw，再 standard，再 alerts
```

### P2 数据库识别 MVP

```text
1. Database Inventory
2. Coverage Verification
3. Semantic Profiler
4. Template Matcher
5. Ingestion Plan Generator
6. Shadow Validator
```

### P3 多源接入扩展

```text
1. HTTP API 拉取
2. Webhook 标准接入
3. CSV / Excel / JSON 文件导入
4. Syslog Collector
```

### P4 告警运营闭环

```text
1. 告警分派
2. 告警处置
3. 通知策略
4. 报表输出
5. 操作审计
6. 反馈样本沉淀
```

### P5 模型辅助

```text
1. 规则评分
2. 结构化模型
3. 本地小模型解释
4. 告警降噪
5. 自学习闭环
```

---

## 15. 文档治理

本手册是项目技术底座的最高优先级文档。

文档优先级：

```text
1. TECHNICAL_MANUAL.md
2. ARCHITECTURE.md
3. DATA_INTEGRATION_WORKFLOW.md
4. README.md
5. 其他历史设计文档
```

如果历史文档中出现以下内容，以本手册为准：

```text
1. 将 EDSP 限定为 IP-Guard 专项平台
2. 将后端主栈描述为 Python FastAPI
3. 绕过 raw/standard/alerts 主链路
4. 要求客户默认手动维护所有字段映射
5. 把 AI 作为当前第一优先级
```

历史文档可以作为参考材料，但不得覆盖本手册定义的技术底座。
