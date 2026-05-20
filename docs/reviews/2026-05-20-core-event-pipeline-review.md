# EDSP Core Event Pipeline Review

> 日期：2026-05-20  
> 审查对象：提交 `340dcfe925041a6eb064d1a1fe9b1a91f90f1027`，提交信息：`前端联调层`  
> 审查范围：多源采集链路、Raw 层、标准事件层、扫描核验、前端联调页

---

## 1. 总体结论

本次提交方向正确，已经开始把 EDSP 从 Demo 告警接入升级为：

```text
collector_adapters / collection_tasks
  ↓
ingestion_runs / ingestion_cursors
  ↓
raw_events / raw_logs / raw_imports
  ↓
standard_events
  ↓
alert_decisions / feedback_labels / alerts
```

这说明项目已经具备多源接入、原始数据留存、标准事件归一化、告警决策和反馈闭环的基础骨架。

当前定位建议为：

```text
多源采集与标准事件管道的数据库表结构 + API 联调层 + 前端操作台雏形
```

还不能视为完整真实采集引擎，因为真实数据库扫描、真实采集任务执行、真实字段识别、真实 raw → standard 自动转换、规则生成告警等能力还未完成。

---

## 2. 做得好的地方

### 2.1 多源接入骨架方向正确

新增了：

```text
collector_adapters
collection_tasks
ingestion_runs
ingestion_cursors
```

这些表符合后续数据库、Syslog、文件、Webhook、API、Agent 等多通道接入的抽象方向。

### 2.2 Raw 层和标准事件层已经分离

新增了：

```text
raw_events
raw_logs
raw_imports
standard_events
```

这避免了外部原始数据直接污染 `alerts`，后续可以支持重放、重新解析、规则重跑和模型训练。

### 2.3 扫描核验方向已经落库

新增了：

```text
schema_scan_runs
```

并记录：

```text
total_databases
scanned_databases
failed_databases
total_tables
scanned_tables
failed_tables
total_fields
scanned_fields
```

这符合“数据库实际 2000 张表，只扫到 200 张必须能发现”的核验目标。

### 2.4 前端联调页面方向正确

`CollectionTasksPage` 已经展示：

```text
采集任务
运行记录
Raw 事件
标准事件
任务健康度
字段变化处理
```

`SchemaPage` 已经体现：

```text
自动扫描
样例 JSON
文件样例
手工补录
字段推荐映射
扫描运行记录
```

整体产品方向正确。

---

## 3. 高优先级问题

### 3.1 时间解析过于严格

当前 `IngestionController` 中的时间解析只使用：

```java
OffsetDateTime.parse(value)
```

这要求时间必须类似：

```text
2026-05-20T09:42:00+08:00
```

但真实数据库、CSV、Syslog、Excel 常见时间格式包括：

```text
2026-05-20 09:42:00
2026/05/20 09:42:00
2026-05-20
2026-05-20T09:42:00Z
```

建议增加兼容解析：

```java
private OffsetDateTime parseTime(String value) {
    if (value == null || value.isBlank()) {
        return null;
    }

    var text = value.trim();

    try {
        return OffsetDateTime.parse(text);
    } catch (Exception ignored) {}

    try {
        return Instant.parse(text).atOffset(ZoneOffset.UTC);
    } catch (Exception ignored) {}

    try {
        return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atOffset(ZoneOffset.ofHours(8));
    } catch (Exception ignored) {}

    try {
        return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay()
            .atOffset(ZoneOffset.ofHours(8));
    } catch (Exception ignored) {}

    throw new IllegalArgumentException("Unsupported time format: " + value);
}
```

---

### 3.2 JSON 字符串缺少后端统一校验

当前多处直接使用：

```sql
cast(? as jsonb)
```

涉及字段包括：

```text
raw_events.payload_json
standard_events.normalized_json
standard_events.extra_json
collection_tasks.config_json
ingestion_runs.quality_report_json
schema_scan_runs.result_json
```

如果传入非法 JSON，会直接数据库异常。

建议后端统一增加 JSON 校验，或将 DTO 字段改为：

```java
Map<String, Object>
JsonNode
```

而不是让前端传 JSON 字符串。

---

### 3.3 standard_events 幂等逻辑不完整

当前依赖：

```sql
unique(source_system, external_id)
```

问题：

1. `external_id` 可以为空。
2. PostgreSQL unique 对多个 NULL 不冲突。
3. 没有 external_id 时无法稳定去重。
4. 空字符串可能导致唯一约束异常。

建议新增：

```sql
alter table standard_events add column if not exists dedup_key varchar(128);

create unique index if not exists uk_standard_events_dedup_key
on standard_events(dedup_key)
where dedup_key is not null;
```

生成规则：

```text
有 external_id：source_system + external_id
无 external_id：source_system + event_type + occurred_at + actor + asset_ref + subject_ref 的 hash
```

---

### 3.4 alerts.standard_event_id 缺少外键和索引

当前只新增了：

```sql
alter table alerts add column if not exists standard_event_id bigint;
```

建议补充：

```sql
alter table alerts
add constraint fk_alerts_standard_event
foreign key (standard_event_id)
references standard_events(id)
on delete set null;

create index if not exists idx_alerts_standard_event
on alerts(standard_event_id);
```

否则从告警追溯标准事件的完整性较弱。

---

### 3.5 limit 参数未做范围限制

多个接口直接接收：

```java
@RequestParam(name = "limit", defaultValue = "100") int limit
```

建议统一限制：

```java
private int safeLimit(int limit) {
    return Math.max(1, Math.min(limit, 500));
}
```

避免传入过大 limit 拖垮页面或数据库。

---

## 4. 中优先级问题

### 4.1 startRun / finishRun 建议加事务

`startRun`、`finishRun`、`createStandardEvent + linkRawEvent` 都涉及多条 SQL，目前没有事务保护。

建议后续抽到 Service 并加：

```java
@Transactional
```

避免出现：

```text
run 创建成功，但 task 状态没更新
run 标记完成，但 cursor 更新失败
task 状态变了，但 quality report 没写完整
```

---

### 4.2 SchemaController 仍是旧模型

V4 已扩展：

```text
schema_tables.scan_run_id
schema_tables.schema_name
schema_tables.table_type
schema_tables.row_count
schema_tables.source_updated_at

schema_fields.scan_run_id
schema_fields.ordinal_position
schema_fields.semantic_type
schema_fields.confidence
schema_fields.is_candidate_key
schema_fields.is_time_candidate
```

但当前 `SchemaController.createTable` 和 `createField` 仍只写旧字段。

建议新增接口：

```text
POST /api/core/schema-scans/{runId}/tables
POST /api/core/schema-scans/{runId}/tables/{tableId}/fields
```

用于真实扫描结果落库。

---

### 4.3 前端 SchemaPage 目前是静态预设模拟

`SchemaPage` 里 `FIELD_PRESETS` 是静态样例，用于联调可以接受。

但建议页面明确：

```text
当前为元数据采集联调模式，真实扫描适配器接入后将自动填充字段。
```

后续应替换为后端真实扫描结果。

---

### 4.4 standardizeRawEvent 可能产生字符串 "null"

当前合并 raw event 时如果数据库值为 null，可能出现：

```java
String.valueOf(raw.get("source_system"))
```

结果变成字符串：

```text
"null"
```

建议改为：

```java
private String stringOrDefault(Object value, String fallback) {
    if (value == null) return fallback;
    var text = String.valueOf(value).trim();
    return text.isEmpty() || "null".equalsIgnoreCase(text) ? fallback : text;
}
```

---

## 5. 后续优化建议

### 5.1 raw_logs / raw_imports 增加 hash 字段

建议新增：

```sql
alter table raw_logs add column if not exists payload_hash varchar(128);
alter table raw_imports add column if not exists payload_hash varchar(128);
alter table raw_imports add column if not exists file_hash varchar(128);
```

用于 Syslog、文件导入、重复上传幂等。

---

### 5.2 Controller 中 SQL 逻辑逐步抽 Service

建议拆分：

```text
IngestionService
CollectionTaskService
SchemaScanService
RawEventService
StandardEventService
```

Controller 只负责 API 参数和响应。

---

## 6. 下一次提交建议清单

建议下一次提交优先修：

```text
1. 修 parseTime，兼容常见数据库 / CSV / 日志时间格式
2. 后端统一 JSON 校验，避免 cast(? as jsonb) 直接 500
3. 给 standard_events 增加 dedup_key，解决 external_id 缺失时的幂等
4. 给 alerts.standard_event_id 补 FK 和索引
5. 所有 limit 参数加 safeLimit
6. collection run / standard event 写入逻辑抽 Service 并加事务
```

---

## 7. 审查结论

本次 push 可以继续保留并推进。它已经从 Demo 页面走向真正的采集链路骨架。

当前最准确定位：

```text
多源采集与标准事件管道的数据库表结构 + API 联调层 + 前端操作台雏形
```

下一阶段目标：

```text
可幂等
可校验
可容错
可事务一致
可真实扫描
可真实采集
可重放
可追溯
```

整体方向正确，建议先修高优先级问题，再继续做真实数据库扫描器和真实采集执行器。

---

## 8. 接下来实施计划

接下来不要再扩展新概念，先进入：

```text
补基础稳定性 + 打通真实最小链路
```

核心目标是先把 EDSP 的地基打稳，尤其是：

```text
真实扫描数据库结构
Raw 事件标准化
采集任务状态一致性
字段映射落库
幂等和容错
```

---

### 8.1 下一步先做 5 件事

#### 1. 先修高优先级问题

优先修改：

```text
1. 时间解析 parseTime 兼容常见格式
2. JSON 入库前统一校验
3. standard_events 增加 dedup_key
4. alerts.standard_event_id 增加外键和索引
5. 所有 limit 参数加 safeLimit
6. startRun / finishRun / createStandardEvent 加事务
```

这一步是为了让当前的 Raw → Standard Event 管道更稳。

---

#### 2. 新增 V5 migration

建议新增文件：

```text
backend/edsp-core/src/main/resources/db/migration/V5__event_pipeline_hardening.sql
```

先补：

```sql
alter table standard_events add column if not exists dedup_key varchar(128);

create unique index if not exists uk_standard_events_dedup_key
on standard_events(dedup_key)
where dedup_key is not null;

alter table alerts add column if not exists standard_event_id bigint;

create index if not exists idx_alerts_standard_event
on alerts(standard_event_id);

alter table raw_logs add column if not exists payload_hash varchar(128);
alter table raw_imports add column if not exists payload_hash varchar(128);
alter table raw_imports add column if not exists file_hash varchar(128);
```

外键可以先加；如果本地已有脏数据导致失败，就先只加索引，等数据清理后再加 FK。

---

#### 3. 把 Controller 核心逻辑抽 Service

先不要全项目重构，优先抽：

```text
IngestionService
CollectionTaskService
SchemaScanService
```

优先处理：

```text
createRawEvent
createStandardEvent
standardizeRawEvent
startRun
finishRun
createScanRun
finishScanRun
```

这些方法需要加：

```java
@Transactional
```

这样后面采集任务、游标、Raw 事件、标准事件不会出现半成功状态。

---

#### 4. 做一个“假真实”的数据库扫描器 MVP

先不要追求适配所有厂家。

先做最小真实流程：

```text
选择一个 SQL Server / PostgreSQL 数据源
  ↓
读取 information_schema.tables
  ↓
读取 information_schema.columns
  ↓
写入 schema_scan_runs
  ↓
写入 schema_tables
  ↓
写入 schema_fields
  ↓
前端 SchemaPage 能看到真实扫描结果
```

下一阶段目标不是 AI 识别，而是：

```text
平台真的能扫库
平台真的能落元数据
平台真的能核验扫了多少表
```

这个做完后，数据库识别基础才成立。

---

#### 5. 做 Raw Event → Standard Event 自动转换 Demo

现在已有接口，但还缺自动转换服务。

先做简单版本：

```text
raw_events.payload_json
  ↓
根据字段映射 field_mappings
  ↓
生成 standard_events
```

例如 raw：

```json
{
  "event_id": "E001",
  "event_name": "疑似敏感文件外发",
  "risk_level": "high",
  "event_time": "2026-05-20 09:42:00",
  "user_name": "张三"
}
```

映射后生成：

```json
{
  "externalId": "E001",
  "eventType": "data_leakage",
  "severity": "high",
  "occurredAt": "2026-05-20 09:42:00",
  "actor": "张三"
}
```

这一步打通后，EDSP 主链路就是：

```text
外部数据 → raw_events → standard_events
```

---

### 8.2 本周目标

这周不要做 AI、XGBoost、复杂 Agent。

本周只完成：

```text
1. V5 migration
2. IngestionService / CollectionTaskService / SchemaScanService
3. safeLimit + JSON 校验 + 时间解析
4. 数据库元数据真实扫描 MVP
5. raw_events 自动标准化 MVP
```

---

### 8.3 做完后的验收标准

按下面标准检查：

```text
1. docker compose up --build 能启动
2. 前端采集任务页面不报错
3. 能创建采集任务
4. 能启动/完成一次 ingestion run
5. 能写入 raw_event
6. 能把 raw_event 转成 standard_event
7. 能登记一次 schema scan run
8. 能扫描真实数据库表和字段
9. 前端 SchemaPage 能看到真实表字段
10. docs/reviews 里的问题至少修掉前 5 个
```

---

### 8.4 当前最该做的一句话

> **先把“真实扫描数据库结构 + Raw 事件标准化”打通。**

这是 EDSP 后续所有 AI、Agent、XGBoost、告警降噪的地基。地基没稳之前，不建议继续往上堆智能能力。
