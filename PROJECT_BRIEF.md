# 企业数据安全分析平台 (EDSP) — 项目需求文档

> 适用于任何AI工具理解项目全貌，可直接分享使用

---

## 一、项目定位

构建一个**企业级数据安全分析预警平台**，接入企业内部所有安全产品日志（IP-Guard DLP为首个数据源），通过AI自学习实现泄密行为自动识别、文件传播链路追溯、领导级汇报材料自动生成。

**核心价值：** 不是数据展示工具，而是分析决策平台。每个模块产出的是安全分析结论，而非原始日志数据。

---

## 二、数据源

### 当前已接入
- **IP-Guard DLP** (SQL Server 2019, 172.16.34.134:1433, 只读账号 ipguard_reader)
  - 主库 OCULAR3: 164张配置表（用户/终端/策略/分类/资产）
  - 日志库 OCULAR3_DATA.*: 18个按天分库，每库93张日志表
  - 总规模: 21数据库, 955张表, 71,450条记录

### 待接入（统一插件接口）
- 杀毒软件 (360天擎/卡巴斯基/火绒/CrowdStrike/Defender)
- 爱数备份系统
- Syslog (防火墙/交换机/路由器/服务器)
- SNMP网络设备
- 门禁/打印机/摄像头/IoT传感器
- **任意其他产品** — 万能数据源自动适配（自动检测CSV/JSON/XML/Syslog/数据库格式）

---

## 三、核心功能需求

### 3.1 泄密行为分析 (核心)

基于IP-Guard日志数据，对以下泄密渠道进行专项分析：

| 渠道 | 数据表 | 泄密检测项 |
|------|--------|-----------|
| 文档操作 | DOC_LOG, DOC_MONITOR_LOG, DOC_FLOWTRACE_LOG | 批量拷贝(>50个)、目标路径异常(USB/网盘/远程)、非工作时间操作、大文件操作、文件批量重命名 |
| USB移动存储 | UDISK_LOG, SECURITY_UDISK_LOG | 批量写入(>30个)、大文件(>100MB)、跨机使用(摆渡攻击)、非加密U盘、非工作时间 |
| 电子邮件 | MAIL_LOG, MAIL_ATTACH, MAIL_PLAIN | 外部域名、大附件(>10MB)、批量发送(>20封)、密送(BCC)、敏感关键词 |
| 打印 | PRINT_LOG, PRINT_RECORD_TEXT | 大量打印(>50页)、虚拟打印机(PDF/XPS)、非工作时间 |
| 网页上传 | URL_LOG, NETWORK_REPORT | 网盘/云存储访问、WebMail附件、异常大流量(>500MB上行) |
| IM即时通讯 | IM_SESSION_LOG, IM_CONTENT_LOG | 文件传输、敏感内容、非工作时间 |
| 加密操作 | ENCRYPT_DOC_LOG, DECRYPT_AUTH_REQUEST_LOG | 安全级别降级、安全域迁移、无审批解密、审批绕过 |
| 敏感内容 | SENSITIVE_LOG, SCI_FTRULE_LIB | 敏感规则触达、涉密文件操作 |

### 3.2 文件传播链路追溯

基于 DOC_FLOWTRACE_LOG 的 FLOWTRACENUM（文件流转唯一编号），实现：
- 文件从创建到外泄的完整路径DAG可视化
- 每个环节：时间、用户、终端、应用、路径变化、文件大小
- 支持任意节点溯源，输出追溯报告

### 3.3 用户行为画像

- 30天行为基线自动建立
- 同类角色对比分析 (Peer Group Analysis)
- 离职风险综合评分 (USB↑+邮件↑+非工作时间↑+大量读取)
- 24小时操作时段分布

### 3.4 领导汇报材料

- 泄密风险综合报告（含8渠道分析 + TOP风险用户 + 趋势图表）
- 事件调查报告（单用户/单文件全维度追溯）
- 周期性报表（日/周/月/季/年，自动生成）
- 合规审计材料（等保/ISO27001，可直接提交审计）
- 支持Excel/PDF/Word格式导出

### 3.5 告警中心

- 实时告警弹窗（检测到即弹）
- 告警分级：严重/高/中/低
- 告警聚合降噪
- 人工标注反馈（小白3步引导）
- 误报率自动追踪

---

## 四、技术架构

### 4.1 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Python FastAPI + SQLAlchemy + Pandas |
| 前端 | React 18 + Ant Design Pro / shadcn-ui |
| 存储 | PostgreSQL (配置) + ClickHouse (亿级日志时序) + Redis (缓存/实时计数) |
| 消息 | Redis Streams / Kafka |
| AI | XGBoost + Isolation Forest (L1), Qwen2.5-7B/14B INT4 (L2/L3) |
| 部署 | Docker Compose (小) → K8s (大) |
| 主题 | 4套 (默认/深色/蓝色/暖色), CSS变量切换 |

### 4.2 多层AI架构 (Harness: Agent = Model + Harness)

```
L1 实时检测: XGBoost + Isolation Forest (本地CPU, <5ms, 处理95%事件)
L2 内容分析: Qwen2.5-7B INT4 (本地GPU, <100ms, 处理4%事件)  
L3 综合研判: Qwen2.5-14B INT4 (本地GPU, <500ms, 处理1%事件)
L4 报告生成: API按需调用 (通义千问/DeepSeek)
```

### 4.3 秒级实时预警 (硬性需求)

- 端到端延迟: 1-5秒 (轻量NOLOCK轮询) / <500ms (CDC事务日志)
- 对源库压力: ≈0.3% (NOLOCK) / ≈0% (CDC)
- WebSocket实时推送KPI、告警、事件流
- 全行业自适应特征权重 (63维×不限行业)

---

## 五、已完成的技术产出

### 设计文档 (12份)
- 企业全数据平台底层架构 (ClickHouse+Flink+Kafka+HA)
- AI自学习闭环设计 (无监督→有监督→反馈→进化)
- 多Agent可扩展架构 (统一NormalizedEvent + 插件体系)
- Harness工程化AI架构
- 全流程数据对接设计 (DB/API/CLI/Syslog + 自动Schema发现)
- 秒级实时预警架构 (WebSocket + Redis Stream + 零压力轮询)
- 隐私Agent网关 (审计+脱敏+API代理, 数据不出内网)
- 全行业自适应模板引擎 (不限行业, 自动学习指纹)
- IP-Guard专项需求 (164条, 基于真实数据库推导)
- 模型选型推荐 (14B本地 vs API对比)
- DOC/ENCRYPT日志深度解析

### 代码引擎 (12个模块)
- 告警引擎 (DOC/USB/MAIL 3渠道 + 跨渠道联动)
- 追溯引擎 (文件流转DAG)
- AI引擎 (评分→解释→推荐→反馈闭环)
- 模型训练器 (Isolation Forest完整实现)
- 标注管理器 (误报率追踪)
- 小白引导标注 (3步标注流程)
- 报表引擎 (调查表 + 5种周期报告)
- 全行业注册中心 (种子模板 + 自学习)
- 隐私Agent (审计+脱敏+API代理)
- 协调器 (Harness消息路由)
- 4个数据源连接器 (IP-Guard/Syslog/杀毒/爱数)
- 万能数据源 (自动检测格式/字段/语义)

### 前端
- IP-Guard风格分析平台 (双击打开)
- 4主题切换 + 平台级导航
- 全部真实数据驱动

### 服务端
- `python run.py` 一键启动 (stdlib零依赖)
- 10+ API端点

---

## 六、待推进任务 (优先级排序)

- P1: 正式环境Agent日志数据到位 → 告警引擎实测校准
- P2: 真实数据驱动: 告警阈值校准 + 30天用户行为基线
- P3: Docker生产部署 + 第二个数据源接入
- P4: AI模型训练 + 自学习闭环激活 (需500+人工标注)
- P5: 14B大模型本地部署 + Harness自然语言交互
- P6: 真实数据报告生成 + Excel报表调优

---

## 七、关键技术决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 存储引擎 | ClickHouse | 亿级OLAP亚秒查询，压缩比10:1 |
| AI架构 | Harness (Agent=Model+Harness) | 可互换Agent，3消息类型，自然语言交互 |
| 模型部署 | 本地GPU (14B INT4) | 数据不出内网，RTX3060约2000元 |
| 实时方案 | NOLOCK轮询 → CDC | Phase1零压力，Phase2零延迟 |
| 前端 | Ant Design Pro + Vite | 中国团队最熟悉的React企业级方案 |
| 行业模板 | 自适应学习 + 种子模板 | 不限行业，从数据自动学习特征权重 |
| 扩展性 | 统一NormalizedEvent + 插件注册 | 任何产品/格式均可接入 |
