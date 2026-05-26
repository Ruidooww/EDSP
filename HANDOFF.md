# 数据安全预警分析平台 Handoff

更新时间：2026-05-26
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`Standard Event Transform Boundary MVP`
- 最新 feature merge commit：`0dce387 merge: standard event transform boundary mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for standard event transform boundary mvp`
- 本轮阶段分支：`codex/standard-event-transform-boundary-mvp`
- 本轮结果：已新增 `backend/edsp-transform` 普通 Maven Java library module，并把 `IngestionPlanSyncOnceService` 中 sync once / scheduled sync 写入 `standard_events` 的标准事件转换核心抽成纯 Java 边界。

## 已完成能力

- 新增普通 Maven module：`backend/edsp-transform`。
  - 作为普通 `jar` library module。
  - 不是 Spring Boot service。
  - 不包含 Spring Boot repackage 配置。
  - compile dependency tree 为空，仅 test scope 使用 JUnit。
- `backend/pom.xml` 已加入：
  - `edsp-common`
  - `edsp-transform`
  - `edsp-core`
- `edsp-core` 已新增对 `edsp-transform` 的单向依赖。
  - 依赖方向为 `edsp-core -> edsp-transform`。
  - 未出现 `edsp-transform -> edsp-core`。
  - 未形成循环依赖。
- 新增 package：`com.edsp.transform.standardevent`。
- 新增纯 Java transform service：
  - `StandardEventTransformService`
  - 核心方法：`TransformResult transform(SourceRow row, MappingPlan plan, TransformOptions options)`
- 新增 transform models：
  - `SourceRow`
  - `MappingPlan`
  - `FieldMapping`
  - `TransformOptions`
  - `StandardEventDraft`
  - `TransformResult`
  - `TransformError`
  - `TransformWarning`
- 新增纯 Java helpers：
  - `TimeValueParser`
  - `SeverityNormalizer`
  - `RiskScoreCalculator`
  - `DedupKeyBuilder`
- `StandardEventDraft` 覆盖现有 `standard_events` 写入所需字段：
  - `sourceSystem`
  - `externalId`
  - `eventType`
  - `occurredAt`
  - `actor`
  - `assetRef`
  - `subjectType`
  - `subjectRef`
  - `action`
  - `result`
  - `severity`
  - `riskScore`
  - `dedupKey`
  - `normalized`
  - `extra`
- `edsp-transform` 返回 draft object / `Map` / errors / warnings，不返回 JSON string。
- `edsp-core` 继续负责：
  - `ObjectMapper` JSON 序列化。
  - `raw_events` 写入。
  - `standard_events` 写入。
  - dedup 查询。
  - raw status 更新。
  - ingestion run / sync run 写入。
  - sync report 构造。
- `edsp-core` 新增小型 Spring config：
  - `TransformConfig`
  - 只负责注册 `StandardEventTransformService` bean。
- `IngestionPlanSyncOnceService` 已从转换执行者收敛为编排者：
  - row loop 保留在 `edsp-core`。
  - raw insert 保留在 `edsp-core`。
  - transform 调用委托给 `edsp-transform`。
  - dedup 查询保留在 `edsp-core`。
  - standard insert 保留在 `edsp-core`。
  - failed row 仍先写 `raw_events`，再更新 `raw_events.status = standardize_failed`。
- 转换行为保持等价：
  - time parsing 保持 ISO datetime、本地时间、日期、epoch 秒、epoch 毫秒、默认 `+08:00` 行为。
  - missing occurredAt 返回 `missing_occurred_at`。
  - invalid occurredAt 返回 `invalid_time_format`。
  - severity 归一保持 `critical/high/medium/low/info`。
  - `warning -> medium`。
  - `1/2/3/4 -> critical/high/medium/low`。
  - unknown severity 仍产生 `severity_unrecognized` error 并默认 `info`。
  - null severity 默认 `info`，不产生 `severity_unrecognized`。
  - risk score 保持 `critical=95/high=80/medium=55/low=25/default=10`。
  - dedup 保持 externalId 优先、configured dedup fields 必须完整、缺失返回 `dedup_key_missing`。
  - configured dedup SHA-256 输入 / 输出通过单元测试锁定。

## 明确未做 / 禁止误解

- 本轮是 boundary refactor，不是 transform capability expansion。
- 未新增 database migration。
- 未新增独立微服务。
- 未新增 `edsp-transform-service`。
- 未新增 Docker 容器。
- 未新增 Gateway 路由。
- 未新增 Nacos 服务。
- 未新增 Kafka / MQ。
- 未新增 HTTP transform API。
- 未新增 MQ transform API。
- 未新增外部数据库类型。
- 未做 MySQL / Oracle / 达梦 / 金仓适配。
- 未做复杂 `transform_rule` 执行。
- 未做 AI 自动映射。
- 未改 frontend。
- 未改 `SchemaPage`。
- 未改 `IngestionPlanPanel`。
- 未改 `IngestionPlanShadowRunService`。
- 未改 `IngestionPlanPrecheckService`。
- 未改 Shadow Validate 语义。
- 未改 Shadow Run 语义。
- 未改 sync once API。
- 未改 scheduled sync API。
- 未改 ingestion plan status 语义。
- 未改 rule evaluation。
- 未改 alert generation。
- 未改 notification。
- 未改 alert lifecycle。
- 未改 existing database schema。
- 未重写 historical data。
- 未做 cleanup。
- 未修改 Docker / compose。
- 未修改 `AGENTS.md`。

## 当前关键边界

- `edsp-transform` 是纯 Java module，不依赖 `edsp-core`。
- `edsp-transform` 不依赖：
  - `CoreRequestSupport`
  - `JdbcTemplate`
  - Spring Web
  - `ResponseStatusException`
  - Flyway
  - Controller
  - Repository
  - `ObjectMapper`
  - raw / standard event DB tables
  - alerts
  - notifications
- `edsp-core` 可以依赖并调用 `edsp-transform`。
- `edsp-transform` 只负责 row -> standard event draft 的纯转换判断。
- `edsp-transform` 不连接外部数据库。
- `edsp-transform` 不扫描 schema。
- `edsp-transform` 不读取 `data_sources` / `schema_tables` / `schema_fields`。
- `edsp-transform` 不写 `raw_events`。
- `edsp-transform` 不写 `standard_events`。
- `edsp-transform` 不写 ingestion runs / sync runs。
- `edsp-transform` 不写 alerts。
- `edsp-transform` 不触发 notifications。
- `IngestionPlanSyncOnceService` 仍负责 orchestration、DB writes、dedup query、report。
- Sync once / scheduled sync 行为必须继续等价。
- ShadowRun / Precheck 仍保留原逻辑，未来如需统一 transform boundary 应单独规划。

## 测试和验证结果

- 阶段分支验证：
  - `mvn -pl edsp-transform test` 通过，`8` tests。
  - `mvn -pl edsp-core -am test` 通过，`118` tests。
  - `npm.cmd run build` 通过；仅有既有 Vite chunk size warning。
  - `mvn -pl edsp-transform dependency:tree -Dscope=compile` 通过，compile dependency tree 仅包含 `com.edsp:edsp-transform`。
  - `git diff --check` 通过。
  - `git status --short --branch` clean after branch commit / push。
- 本轮新增 / 覆盖测试：
  - simple field mappings -> `StandardEventDraft`。
  - missing occurredAt -> `missing_occurred_at`。
  - invalid occurredAt -> `invalid_time_format`。
  - severity `critical/high/medium/low/info` 归一。
  - severity `warning -> medium`。
  - severity `1/2/3/4` 归一。
  - unknown severity -> `severity_unrecognized` 且默认 `info`。
  - null severity -> 默认 `info` 且不报 `severity_unrecognized`。
  - risk score 分值保持等价。
  - externalId dedup 优先。
  - configured dedup fields 缺失 -> `dedup_key_missing`。
  - configured dedup fields present -> stable SHA-256 key。
  - normalized map 包含 `sourceTable` 和 `mapped`。
  - extra map 包含 `syncMode` / `sourceTable` / `dataSourceId`。
  - transform errors 非空时仍尽量返回 draft。
- Post-merge / push 后 Git 检查结果记录在最终回复中。

## 已知后续项

- 本轮只抽 sync once / scheduled sync 写 `standard_events` 的转换核心。
- `IngestionPlanShadowRunService` 和 `IngestionPlanPrecheckService` 暂未接入 `edsp-transform`。
- 如果后续要让 ShadowRun / Precheck / Sync 使用同一转换边界，需要单独规划一致性阶段。
- 如果后续要拆成独立 `edsp-transform-service`，可以基于本轮 Java boundary 包装 HTTP / MQ 接口，但这不是本轮内容。
- 当前 `docker-compose.yml` 固定 `container_name` 会导致不同 compose project 无法并行启动同一套 EDSP 容器；如需处理，应单独规划 `Docker Compose Container Name Hardening MVP`，不要混入 transform boundary 阶段。

## 下一轮建议

如果继续推进 transform 主线，建议下一阶段进入：

```text
Standard Event Transform Shadow/Precheck Alignment MVP
```

目标建议：

- 评估并逐步统一 ShadowRun / Precheck / Sync 对标准事件字段、时间、severity、dedup 的转换判断口径。
- 保持 Shadow Validate / Precheck 语义不变。
- 不新增 database migration。
- 不新增独立微服务。
- 不新增 HTTP / MQ transform API。
- 不改 rule evaluation / alert generation / notification / alert lifecycle。

如果业务优先级回到通知数据安全，则可单独排期：

- `Notification Secret Historical Cleanup Planning MVP`
- `Docker Compose Container Name Hardening MVP`

下一阶段仍必须遵守：

```text
One stage.
One branch.
One scope.
One full verification.
One merge.
One post-merge HANDOFF update.
Clean master before continuing.
```
