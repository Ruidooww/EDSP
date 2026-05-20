# 全流程数据对接体系

> 数据库直连 | API对接 | CLI工具 | 控制台管理 | 自动发现数据字典

---

## 一、数据接入全流程

```
用户操作                       系统自动                         产出
────────                      ────────                        ────

① 添加数据源
   ├─ 选择产品类型              → 加载预置模板
   ├─ 填写连接信息              → 测试连通性
   └─ 选择认证方式              → 加密存储凭据
                                      │
② 自动发现                              ▼
   (系统自动执行)               → 扫描表/集合/索引
                                → 提取字段名+类型+注释
                                → 采样数据(前100行)
                                → 生成数据字典草稿
                                      │
③ 人工确认                              ▼
   ├─ 审阅自动发现的表清单
   ├─ 标记核心表 vs 忽略表
   ├─ 确认字段映射关系
   └─ 设置敏感字段脱敏规则
                                      │
④ 配置同步策略                          ▼
   ├─ 选择同步模式(实时/定时/手动)
   ├─ 设置增量字段
   ├─ 配置数据保留策略
   └─ 设置告警(延迟/断连)
                                      │
⑤ 激活运行                              ▼
                                → 首次全量同步
                                → 持续增量同步
                                → 健康监控
                                → 数据质量报告
```

---

## 二、接入方式矩阵

### 2.1 四种接入方式

| 方式 | 适用场景 | 实时性 | 复杂度 | 典型产品 |
|------|---------|--------|--------|---------|
| **数据库直连** | 有数据库只读权限 | 分钟级(T+1) | ⭐ 低 | IP-Guard, 爱数 |
| **REST API** | 产品提供API | 秒级 | ⭐⭐ 中 | 360天擎, CrowdStrike |
| **Syslog/File** | 网络设备/服务器 | 实时 | ⭐ 低 | 防火墙, Linux |
| **CLI Agent** | 无API/无DB权限 | 按需 | ⭐⭐⭐ 高 | 遗留系统 |

### 2.2 数据库直连流程（以 IP-Guard 为例）

```
管理控制台                          SQL Server
─────────                          ──────────

1. 填写连接信息:
   ┌─────────────────────────┐
   │ 产品: IP-Guard           │
   │ 类型: SQL Server         │
   │ 主机: 172.16.34.134     │
   │ 端口: 1433              │
   │ 账号: ipguard_reader     │
   │ 密码: ********            │
   │ [测试连接] [保存]        │
   └─────────────────────────┘
            │
            ▼ 测试连接
   ┌─────────────────────────┐     ──→ SELECT 1
   │ ✅ 连接成功              │ ←── 返回: OK
   │ SQL Server 2019         │
   │ 发现 21 个数据库         │
   └─────────────────────────┘
            │
            ▼ 自动发现
   ┌─────────────────────────┐     ──→ SELECT name FROM sys.databases
   │ 发现数据库:              │         WHERE name LIKE 'OCULAR3%'
   │ ☑ OCULAR3 (主配置库)     │
   │ ☑ OCULAR3_DATA.* (18个)  │     ──→ SELECT TABLE_NAME FROM
   │ ☑ OCULAR3_REPORT2        │         INFORMATION_SCHEMA.TABLES
   │ ☑ OCULAR3_LATTICE       │
   │ 共 21 个数据库, 953 张表  │     ──→ SELECT COLUMN_NAME, DATA_TYPE
   └─────────────────────────┘         FROM INFORMATION_SCHEMA.COLUMNS
            │                          WHERE TABLE_NAME = 'DOC_LOG'
            ▼ 人工确认
   ┌─────────────────────────┐
   │ 核心日志表:              │
   │ ☑ DOC_LOG (文档操作)     │
   │ ☑ UDISK_LOG (USB)       │
   │ ☑ MAIL_LOG (邮件)       │
   │ ☑ PRINT_LOG (打印)      │
   │ ☑ URL_LOG (网页)        │
   │ ☐ AGENT_LOG (忽略)      │
   │ [确认映射]               │
   └─────────────────────────┘
            │
            ▼ 字段映射确认
   ┌─────────────────────────────────────────┐
   │ DOC_LOG 字段映射:                        │
   │ 用户列: DOC_USR_ID → actor.user_id      │
   │ 时间列: DOC_TIME → timestamp            │
   │ 操作列: DOC_TYPE → action               │
   │ 路径列: DOC_SRC_PATH → target.file_path │
   │ 大小列: DOC_FILE_SIZE → target.file_size│
   │ [自动推断] [手动修改]                     │
   └─────────────────────────────────────────┘
```

### 2.3 API 接入流程（以 360 天擎为例）

```
管理控制台                          360天擎 API
─────────                          ────────────

1. 填写API信息:
   ┌─────────────────────────┐
   │ 产品: 360天擎            │
   │ 类型: REST API           │
   │ 地址: https://360.example.com│
   │ 认证: API Key            │
   │ Key: sk-****             │
   │ [测试连接]               │
   └─────────────────────────┘
            │
            ▼ 调用 /api/v1/info
   ┌─────────────────────────┐
   │ ✅ 连接成功              │
   │ 产品版本: 天擎V10        │
   │ 终端数: 1,250            │
   │ 可用端点:                │
   │  /api/v1/events (事件)   │
   │  /api/v1/threats (威胁)  │
   │  /api/v1/assets (资产)   │
   └─────────────────────────┘
            │
            ▼ 配置拉取策略
   ┌─────────────────────────┐
   │ 同步模式: 定时拉取        │
   │ 间隔: 5分钟              │
   │ 批量大小: 1000条/次      │
   │ 增量字段: event_time     │
   │ 保留天数: 90天           │
   └─────────────────────────┘
```

---

## 三、CLI 工具设计

### 3.1 命令行对接工具

```bash
# edsp CLI — 企业数据安全平台命令行工具

# 安装
pip install edsp-cli

# 初始化配置
edsp config init --server http://localhost:8000 --token <api_key>

# ── 数据源管理 ──

# 列出所有数据源
edsp source list
# ┌──────────┬──────────┬──────────┬──────────┐
# │ 名称      │ 类型      │ 状态      │ 事件数    │
# ├──────────┼──────────┼──────────┼──────────┤
# │ IP-Guard │ SQL Server│ ✅ 运行中 │ 12.5M   │
# │ 360天擎   │ REST API  │ ✅ 运行中 │ 3.2M    │
# │ 防火墙     │ Syslog   │ ✅ 运行中 │ 45.8M   │
# └──────────┴──────────┴──────────┴──────────┘

# 添加新数据源 (交互式引导)
edsp source add
# ? 选择产品类型: [数据库] API Syslog 文件 自定义
# ? 数据库类型: SQL Server
# ? 主机地址: 172.16.34.134
# ? 端口: 1433
# ? 用户名: ipguard_reader
# ? 密码: ********
# ? 数据库名: OCULAR3
#   🔍 正在连接... ✅
#   🔍 发现 164 张表
#   🔍 自动识别 12 个核心日志表
# ? 确认添加? [Y/n]

# 添加数据源 (非交互式)
edsp source add \
  --type sqlserver \
  --host 172.16.34.134 \
  --port 1433 \
  --user ipguard_reader \
  --password ipg@1234 \
  --database OCULAR3 \
  --product ipguard

# 查看数据源详情
edsp source show ipguard
# 输出完整的数据字典和表结构

# 测试连接
edsp source test ipguard
# ✅ Connection OK | Latency: 12ms | Tables: 164

# 同步数据
edsp source sync ipguard --since "2026-05-01" --mode incremental

# 导出数据字典
edsp source schema ipguard --format markdown --output schema.md
edsp source schema ipguard --format excel --output schema.xlsx

# ── Agent 管理 ──

edsp agent list                    # 列出所有Agent
edsp agent train file_agent        # 训练指定Agent
edsp agent stats file_agent        # Agent准确率统计

# ── 告警查询 ──

edsp alert list --severity high --since "2026-05-01"
edsp alert show ALT-20260509-0042

# ── 报告导出 ──

edsp report generate --type weekly --output weekly_report.xlsx
edsp report generate --type incident --user-id 1001 --output incident.docx
```

---

## 四、管理控制台设计

### 4.1 数据源管理页面

```
┌─────────────────────────────────────────────────────────────┐
│  🔌 数据源管理                              [+ 添加数据源]    │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 🟢 IP-Guard                    SQL Server │ 已运行 3天 │   │
│  │    172.16.34.134:1433         21库 953表   │ 12.5M事件 │   │
│  │    [详情] [同步] [Schema] [暂停] [删除]              │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 🟢 360天擎                      REST API  │ 已运行 1天 │   │
│  │    https://360.example.com     3端点       │ 3.2M事件   │   │
│  │    [详情] [同步] [测试] [暂停] [删除]                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 🟡 爱数(适配中)                  数据库   │ 配置中      │   │
│  │    192.168.1.50:3306          待发现      │ 0事件       │   │
│  │    [配置] [测试连接] [删除]                            │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 🔴 防火墙 Syslog                UDP 514  │ 连接失败    │   │
│  │    0.0.0.0:514                端口占用    │ 0事件       │   │
│  │    [重试] [修改配置] [删除]                            │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Schema 自动发现结果页

```
┌─────────────────────────────────────────────────────────────┐
│  📋 IP-Guard — 数据字典                                     │
│  自动发现时间: 2026-05-09 14:30  |  953表 |  已确认 12表     │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  搜索: [                ]  [全部] [已确认] [待确认] [已忽略]  │
│                                                             │
│  ☑ DOC_LOG             26字段  0行  [文件操作日志]          │
│    用户列: DOC_USR_ID ✓  时间列: DOC_TIME ✓                 │
│    核心字段: DOC_TYPE, DOC_SRC_PATH, DOC_DEST_PATH,         │
│              DOC_FILE_SIZE, DOC_APP_NAME                    │
│    [查看全部字段] [编辑映射] [标记为核心]                     │
│                                                             │
│  ☑ UDISK_LOG           35字段  0行  [USB存储日志]           │
│    用户列: UD_USR_ID ✓   时间列: UD_TIME ✓                  │
│    [查看全部字段] [编辑映射]                                  │
│                                                             │
│  ☑ MAIL_LOG            28字段  0行  [邮件日志]              │
│    [查看全部字段] [编辑映射]                                  │
│                                                             │
│  ☐ AGENT_LOG           13字段  0行  [Agent状态日志]         │
│    ⚠️ 未发现用户列     ⚠️ 可能不是核心日志表                 │
│    [手动指定] [忽略此表]                                     │
│                                                             │
│  ☐ AUTH_REQUEST_LOG    40字段  0行  [审批请求日志]          │
│    [手动指定] [忽略此表]                                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 五、凭据安全管理

```
凭据存储策略:
  ├── 传输: TLS 1.3 加密
  ├── 存储: AES-256-GCM 加密 (密钥存于环境变量/密钥管理服务)
  ├── 内存: 使用后立即清除明文
  ├── 访问控制: RBAC (只有管理员可查看/修改)
  └── 审计: 所有凭据访问操作记录日志

账户类型:
  ├── 只读账户 (推荐) — 只有 SELECT 权限
  ├── API Key — 最小权限原则
  └── 服务账户 — 专用账户，定期轮换密码

# 凭据存储结构
{
  "source_id": "ipguard_01",
  "credentials": {
    "type": "sqlserver",
    "host": "172.16.34.134",
    "port": 1433,
    "username": "ipguard_reader",
    "password_encrypted": "AES256...",
    "encryption_key_id": "key_2026"
  },
  "rotation": {
    "last_rotated": "2026-05-01",
    "rotation_days": 90,
    "next_rotation": "2026-07-30"
  },
  "audit": {
    "created_by": "admin",
    "created_at": "2026-05-01T10:00:00Z",
    "last_used": "2026-05-09T14:30:00Z"
  }
}
```

---

## 六、通用接口规范

### 6.1 数据源注册 API

```yaml
POST /api/sources/register
  Request:
    product: "ipguard" | "aishu" | "antivirus" | "syslog" | "custom"
    connection:
      type: "sqlserver" | "mysql" | "postgresql" | "rest_api" | "syslog" | "file"
      host: string
      port: integer
      username: string
      password: string       # 仅传输时使用，存储后立即加密
      database: string       # 数据库类型时
      api_base: string       # API类型时
    options:
      sync_mode: "incremental" | "full" | "manual"
      sync_interval_minutes: integer
      retention_days: integer
      tables_filter: [string] # 可选，只同步指定表
  
  Response:
    source_id: "src_abc123"
    status: "connected"
    discovered:
      databases: 21
      tables: 953
      core_tables: 12
```

### 6.2 Schema 查询 API

```yaml
GET /api/sources/{source_id}/schema
  Response:
    tables:
      - name: "DOC_LOG"
        columns: [...]
        row_count: 0
        suggested_category: "file_operation"
        user_column: "DOC_USR_ID"      # 自动推断
        time_column: "DOC_TIME"         # 自动推断
        status: "confirmed" | "pending" | "ignored"

POST /api/sources/{source_id}/schema/mapping
  Request:
    table: "DOC_LOG"
    mappings:
      user_column: "DOC_USR_ID"
      time_column: "DOC_TIME"
      action_column: "DOC_TYPE"
      target_path_column: "DOC_DEST_PATH"
      file_size_column: "DOC_FILE_SIZE"
```

### 6.3 CLI 接口

```bash
# 所有管理操作都有对应的CLI命令
edsp source add       → POST /api/sources/register
edsp source list      → GET /api/sources
edsp source show      → GET /api/sources/{id}
edsp source schema    → GET /api/sources/{id}/schema
edsp source sync      → POST /api/sources/{id}/sync
edsp source test      → POST /api/sources/{id}/test
```
