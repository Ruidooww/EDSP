# EDSP 通用告警分析平台任务流

## 平台定位

EDSP 是企业数据安全预警分析平台，不绑定某一个厂商或某一个数据库。IPGUARD、UEBA、DLP、数据库审计、堡垒机、API 推送、Webhook、文件导入都只是外部数据源或接入方式。

平台核心目标是把不同来源的风险事件先进入原始数据层，再归一化成标准事件模型，最后完成规则分析、告警处置、通知推送和报表输出。

当前权威技术底座以 [TECHNICAL_MANUAL.md](./TECHNICAL_MANUAL.md) 为准。

## 标准接入链路

1. 外部系统产生事件或告警。
2. 接入层通过 API、Webhook、数据库采集、文件导入等方式接收数据。
3. 采集适配器把原始数据写入 `raw_events`、`raw_logs` 或 `raw_imports`。
4. 字段映射与标准化服务生成 `standard_events`。
5. 规则中心基于 `standard_events` 做风险判断和告警生成。
6. 告警中心按来源和外部 ID 做幂等入库与状态流转。
7. 通知中心按策略推送到企微、飞书、短信、邮件或 Webhook。
8. 报表中心输出日报、周报、月报和处置统计。

## 当前阶段已经落地

- Java 21 + Spring Boot 3 + Spring Cloud 多模块后端。
- React + TypeScript + Ant Design 前端控制台。
- 本地 H2 开发库，后续可切 PostgreSQL。
- SQL Server 数据源配置和连接测试能力。
- 标准告警表字段扩展。
- 标准告警接入 API：
  - `POST /api/ingest/alerts`
  - `POST /api/ingest/alerts/batch`
- 告警幂等逻辑：同一个 `sourceSystem + externalId` 再次上报时更新原告警。
- 前端告警中心展示标准模型字段，不绑定具体厂商名称。
- 通知中心基础能力：
  - `GET /api/notifications/channels`
  - `POST /api/notifications/channels`
  - `POST /api/notifications/channels/{id}/test`
  - `POST /api/notifications/send`
- Webhook 通道已可测试发送，并记录发送结果。
- 元数据快照和字段映射已作为数据源下的二级能力推进。
- 外部接入、采集任务已明确为数据源下的二级菜单。

## 标准告警字段

| 字段 | 说明 |
|---|---|
| `sourceSystem` | 来源系统，例如 `ueba`、`dbaudit`、`dlp`、`ipguard`、`demo-api` |
| `externalId` | 外部系统告警 ID，用于幂等更新 |
| `alertType` | 告警类型，例如敏感字段访问、异常导出、权限越权 |
| `title` | 告警标题 |
| `severity` | `critical`、`high`、`medium`、`low`、`info` |
| `occurredAt` | 事件发生时间 |
| `actor` | 触发主体，例如账号、员工、应用 |
| `asset` | 相关资产，例如数据库、表、系统、服务器 |
| `policyName` | 命中的策略或规则名称 |
| `subjectType` | 对象类型，例如 `database`、`table`、`file`、`api` |
| `subjectRef` | 对象标识，例如 `customer.phone` |
| `status` | `open`、`processing`、`resolved`、`closed` |
| `detail` | 原始详情或扩展字段 |

注意：这些字段属于告警展示和处置模型。正式链路中不能用外部数据直接填充告警模型，必须先经过 raw 层和 `standard_events`。

## 下一步优先级

1. 完善告警处置流：状态变更、备注、负责人、处置时间、关闭原因。
2. 做告警联动通知：按严重等级、来源系统、告警类型自动选择通知通道。
3. 扩展企微、飞书、短信、邮件适配器。
4. 做规则中心增强：业务模板、参数化规则、启停、级别调整、来源过滤。
5. 建立 raw 层、`standard_events`、采集任务、采集运行记录和扫描完整性核验。
6. 再补具体采集适配器，例如 SQL Server 表轮询、第三方 API 拉取、Webhook 推送、文件导入。
7. 补认证权限和审计日志，避免平台接口裸奔。

## 当前不优先做

- 不依赖 IPGUARD 主数据库作为唯一入口。
- 不在数据库全部不可达时继续硬做库表扫描。
- 不把 UEBA 内部读取过程放到前台主流程展示。
- 不急着接 AI 分析，先把标准数据链路跑稳。
