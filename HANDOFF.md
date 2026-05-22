# 数据安全预警分析平台 Handoff

> 更新时间：2026-05-22
> 当前分支：`codex/database-intelligence-ingestion-plan`
> 项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`
> 当前阶段：`Database Intelligence Ingestion Plan MVP`

## 1. 当前项目定位

本项目是“数据安全预警分析平台”，不是 IP-Guard 专用平台，也不是单纯数据库读取工具。

产品主线必须保持为：

```text
外部系统 / 数据库 / API / Webhook / 文件 / Syslog / Agent
  -> 采集适配器
  -> raw_events / raw_logs / raw_imports
  -> 字段映射与标准化
  -> standard_events
  -> 规则 / 风险判断
  -> alerts
  -> 通知 / 处置 / 报表 / 反馈
```

当前阶段的核心目标不是“直接生成告警”，而是：

```text
从已扫描元数据中，自动推荐哪些表值得接入、字段怎么映射、方案是否可信、是否可以进入试运行准备。
```

## 2. 本阶段硬性边界

本阶段只实现 `Database Intelligence Ingestion Plan MVP`，不扩展到以下内容：

- 不做 AI / XGBoost / 复杂 Agent。
- 不做完整 Shadow Validator。
- 不做正式规则引擎闭环。
- 不做正式通知推送链路。
- 不把 `shadow_ready` 当作正式启用。

必须遵守 3 条底线：

1. 不覆盖已有人工映射。
2. 只对 `alert_table` / `log_table` 生成主接入方案。
3. `shadow_ready` 不是正式启用状态。

## 3. 当前工作区状态

当前未提交文件：

```text
 M HANDOFF.md
 M frontend/src/pages/SchemaPage.tsx
 M frontend/src/types.ts
?? backend/edsp-core/src/main/java/com/edsp/core/controller/IngestionPlanController.java
?? backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanGenerateRequest.java
?? backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanShadowValidationRequest.java
?? backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanStatusRequest.java
?? backend/edsp-core/src/main/java/com/edsp/core/service/IngestionPlanService.java
?? backend/edsp-core/src/main/java/com/edsp/core/service/SemanticProfilerService.java
?? backend/edsp-core/src/main/java/com/edsp/core/service/TemplateMatcherService.java
?? backend/edsp-core/src/test/java/com/edsp/core/service/IngestionPlanServiceTest.java
?? backend/edsp-core/src/test/java/com/edsp/core/service/IngestionPlanServiceTransactionTest.java
```

建议后续提交信息：

```text
feat: add database intelligence ingestion plan MVP
```

## 4. 本阶段新增后端能力

### 4.1 新增 API

新增控制器：

```text
backend/edsp-core/src/main/java/com/edsp/core/controller/IngestionPlanController.java
```

接口：

```text
GET  /api/core/ingestion-plans?dataSourceId=&status=
POST /api/core/ingestion-plans/generate
PUT  /api/core/ingestion-plans/{id}/status
POST /api/core/ingestion-plans/{id}/shadow-validate
```

请求 DTO：

```text
backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanGenerateRequest.java
backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanShadowValidationRequest.java
backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanStatusRequest.java
```

### 4.2 SemanticProfilerService

新增：

```text
backend/edsp-core/src/main/java/com/edsp/core/service/SemanticProfilerService.java
```

职责：

- 根据字段名、字段类型、已有描述识别字段语义。
- 识别 `externalId`、`occurredAt`、`severity`、`actor`、`assetRef`、`title`、`alertType` 等核心标准字段。
- `plain`、`sensitive_value`、`detail` 只作为字段语义分类，不自动映射为核心标准字段。
- 自动补充 `schema_fields.semantic_type`、`confidence`、`description`。
- 不覆盖已有人工映射：如果 `field_mappings` 已存在，则保留人工映射，只补充证据到方案 JSON。
- 不用低置信度结果覆盖更高置信度的既有语义。

注意：`HOST_NAME` / `ASSET` / `DEVICE` / `IP` 等资产类字段优先识别为 `assetRef`，避免被宽泛的 `NAME` 规则误判为 `title`。
注意：`USER_ID` / `ASSET_ID` / `HOST_ID` 等主体或资产 ID 不作为外部事件 ID，避免误用为 `externalId` dedup key。

### 4.3 TemplateMatcherService

新增：

```text
backend/edsp-core/src/main/java/com/edsp/core/service/TemplateMatcherService.java
```

职责：

- 判断表更像 `alert_table`、`log_table` 还是辅助维表。
- `alert_table` 和 `log_table` 才是主接入方案候选。
- `user_table`、`asset_table`、`policy_table`、`detail_table` 只作为辅助识别结果，不生成主方案。
- `log` / `audit` / `trace` / `operation` / `access` 优先于 `alert` / `risk` / `event`，避免 `EVENT_AUDIT_LOG` 被误判成告警表。
- 启发式匹配使用表名、分类和字段名 token，不使用 substring 命中，避免 `LOGIN_NAME` 里的 `log` 误触发 `log_table`。

### 4.4 IngestionPlanService

新增：

```text
backend/edsp-core/src/main/java/com/edsp/core/service/IngestionPlanService.java
```

职责：

- 从 `schema_tables` / `schema_fields` / `field_mappings` 生成 `ingestion_plans`。
- 生成完整 `plan_json`。
- 控制方案去重、覆盖、状态流转。
- 保持 `approved` / `shadow_ready` 方案不被重新生成直接覆盖。
- 保持 `rejected` 方案只作为历史记录，不被自动复活或恢复。

生成逻辑是事务性的：

```text
@Transactional
public List<Map<String, Object>> generate(...)
```

### 4.5 轻量 Shadow Precheck / 试运行前校验

新增能力：

```text
POST /api/core/ingestion-plans/{id}/shadow-validate
```

当前只是试运行前校验，不读取真实业务表样本行，不写入 `standard_events` / `alert_decisions` / `alerts`，不触发通知，也不会自动把方案启用。

校验规则：

- 只允许 `approved` / `shadow_ready` 方案执行试运行前校验。
- `sampleLimit` 默认 50，最大 100。
- 校验 `mode = database_polling`、源表仍活跃、映射源字段仍存在、必要字段完整、dedup 策略可评估、`syncStrategy.shadowOnly = true` 且 `enabled = false`。
- 返回 `result = passed | warning | blocked`、`statusRecommendation`、`blockers`、`warnings`、`standardEventPreview` 和逐项 `checks`。
- 缺少 `occurred_at` 或 dedup key 来源不足时返回 `blocked`，推荐回到 `manual_review`。

## 5. plan_json 结构约束

`plan_json` 当前包含：

```json
{
  "version": "ingestion-plan-v1",
  "generatedBy": "database-intelligence-mvp",
  "generatedAt": "2026-05-22T00:00:00Z",
  "mode": "database_polling",
  "confidence": 86,
  "coverageConfidence": 80,
  "mappingConfidence": 88,
  "mainTable": "OME_UD_3_LOG",
  "schemaTableId": 1,
  "cursorField": "CREATE_TIME",
  "idField": "ID",
  "dedupStrategy": {
    "type": "external_id",
    "fields": ["ID"],
    "fallback": "composite"
  },
  "fieldMappings": {
    "ID": "externalId",
    "CREATE_TIME": "occurredAt"
  },
  "fieldMappingDetails": [],
  "fieldEvidence": {},
  "templateMatch": {},
  "syncStrategy": {
    "shadowOnly": true,
    "enabled": false,
    "activation": "requires_status_shadow_ready"
  },
  "risks": [],
  "requiredFieldsMissing": [],
  "recommendedAction": "shadow_validate"
}
```

关键约束：

- `dedupStrategy.fields` 使用来源字段名，不使用标准字段名。
- `templateMatch.confidence` 表示“表像不像告警/日志表”。
- `confidence` 表示“整个接入方案是否值得进入试运行”。
- `fieldEvidence` 记录字段识别原因。
- `fieldMappingDetails` 保留 `transformRule`。
- `recommendedAction` 只能是以下枚举：

```text
shadow_validate
manual_review
needs_mapping
insufficient_coverage
reject
```

置信度第一版公式：

```text
confidence = templateMatch.confidence * 0.4
           + mappingConfidence * 0.4
           + coverageConfidence * 0.2
```

严重缺失字段会扣分：

```text
missing occurred_at: -30
dedup_key_source_insufficient: -30
limited scan: recommendedAction = insufficient_coverage
```

## 6. dedup 策略

优先使用外部 ID：

```json
{
  "type": "external_id",
  "fields": ["ID"],
  "fallback": "composite"
}
```

没有外部 ID 时，尝试组合去重：

```json
{
  "type": "composite",
  "fields": ["EVENT_TYPE", "CREATE_TIME", "USER_ACCOUNT", "HOST_NAME"],
  "stable": true
}
```

`external_id` 不是绝对必填。

真正阻断方案的是：

```text
dedup_key_source_insufficient
```

也就是既没有外部 ID，也无法稳定构造组合 dedup key。

## 7. 状态机

状态白名单：

```text
suggested
review_required
approved
shadow_ready
rejected
```

允许流转：

```text
suggested       -> review_required
suggested       -> approved
suggested       -> rejected

review_required -> approved
review_required -> rejected

approved        -> shadow_ready
approved        -> rejected

shadow_ready    -> rejected
```

禁止：

```text
shadow_ready -> approved
```

说明：

- `approved` 表示方案被人工批准。
- `shadow_ready` 表示可以进入试运行准备。
- `shadow_ready` 不是正式启用。
- 第一版没有 `enabled`。
- 前端对 `approved` / `shadow_ready` 的撤销文案应使用“废弃方案”，后端状态仍落到 `rejected`。

## 8. 方案生成去重策略

同一类方案匹配维度：

```text
data_source_id + schema_table_id + mode + templateKey
```

当前实现规则：

- 已有 `suggested` / `review_required`：允许更新同一方案。
- 已有 `approved` / `shadow_ready`：不覆盖，生成新的 `review_required` 方案，并在 `risks` 中提示已有已批准方案。
- 已有 `rejected`：自动生成时不覆盖、不复活、不复用，重新生成新的 `suggested` / `review_required` 方案。
- `rejected` 是历史终态，不支持恢复为活动方案。
- 老版本已生成方案里 `mode` 如果等于 `templateKey`，去重时按 `database_polling` 兼容处理，避免升级后重复生成。

## 9. 前端变更

修改：

```text
frontend/src/pages/SchemaPage.tsx
frontend/src/types.ts
```

新增能力：

- 在“元数据快照”页展示推荐接入方案。
- 支持生成推荐方案。
- 支持查看字段识别原因、模板匹配、风险、缺失字段。
- 支持方案状态操作：标记复核、批准方案、进入试运行准备、废弃方案。
- 支持对 `approved` / `shadow_ready` 方案触发“试运行前校验 / Shadow Precheck”，并在抽屉中展示校验结果、阻断项、提醒项、标准事件预览和逐项检查。
- `shadow_ready` 展示为“试运行准备”，不展示成“已启用”。
- `rejected` 状态只保留“查看原因”，重新生成统一走顶部“生成推荐方案”。
- 前端只提交后端允许的状态值，不再发送 `discarded` / `draft` 等非法状态。
- 解析 `plan_json.fieldEvidence`，支持 object / array 两种结构。
- 针对长标签、长字段、长原因做换行处理，降低横向溢出风险。

类型新增：

```text
IngestionPlanRow
IngestionPlanJson
IngestionPlanFieldEvidence
IngestionPlanMappingDetail
IngestionPlanDedupStrategy
IngestionPlanShadowValidationCheck
IngestionPlanShadowValidationReport
```

## 10. 已覆盖测试

新增测试：

```text
backend/edsp-core/src/test/java/com/edsp/core/service/IngestionPlanServiceTest.java
backend/edsp-core/src/test/java/com/edsp/core/service/IngestionPlanServiceTransactionTest.java
```

当前覆盖点：

- `alert_table` 可生成主接入方案。
- `log_table` 可生成主接入方案。
- `user_table` / `asset_table` / `policy_table` 不生成主接入方案。
- `EVENT_AUDIT_LOG` 优先识别为 `log_table`。
- `SYS_USER_PROFILE` / `LOGIN_NAME` 不会因 `login` 子串误生成 `log_table` 主方案。
- 不覆盖已有人工字段映射。
- 保留已有 `field_mappings.transform_rule`。
- `plan_json.mode` 固定为 `database_polling`，模板类型保留在 `templateMatch.templateKey`。
- limited scan 时推荐动作变为 `insufficient_coverage`。
- 没有外部 ID 但能构造组合 dedup key 时，不直接判定不可用。
- `USER_ID` 不会被识别为 `externalId` dedup key，会作为主体字段参与组合 dedup。
- API 返回的 `plan_json` 是结构化对象，不再在 H2/JDBC 场景下透传为 base64 字符串。
- `suggested` / `review_required` 可被重复生成更新。
- `approved` / `shadow_ready` 不被重复生成覆盖。
- `rejected` 不被自动覆盖、复活或复用；再次 generate 会新增新的 `suggested` / `review_required` 方案。
- `rejected` 不允许流转回 `suggested`。
- 禁止 `shadow_ready -> approved`。
- Spring 事务代理级验证：`generate()` 后半段失败时，前面写入的 `schema_fields.semantic_type` 会回滚。
- `shadow-validate` 会拒绝未人工批准的 `suggested` 方案。
- `shadow-validate` 对已批准方案只返回 Shadow Precheck 报告，不写入 `standard_events` / `alert_decisions` / `alerts`，也不改变方案状态。
- `shadow-validate` 对缺少必要字段的已批准方案返回 `blocked` 和明确阻断项。

## 11. 已运行验证

后端定向测试：

```powershell
cd backend
mvn -pl edsp-core -am -Dtest=IngestionPlanServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

```text
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

后端事务回滚定向测试：

```powershell
cd backend
mvn -pl edsp-core -am -Dtest=IngestionPlanServiceTransactionTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

红灯验证：临时移除 `IngestionPlanService.generate()` 上的 `@Transactional` 后，该测试失败，`schema_fields.semantic_type` 残留 5 条脏更新；恢复注解后通过。

后端受影响模块完整测试：

```powershell
cd backend
mvn -pl edsp-core -am test
```

结果：

```text
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

前端类型检查：

```powershell
cd frontend
& 'C:\Users\Ruidoww\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' 'C:\Users\Ruidoww\Desktop\预警分析平台推送对接\frontend\node_modules\typescript\bin\tsc' --noEmit
```

结果：

```text
success
```

前端构建：

```powershell
cd frontend
& 'C:\Users\Ruidoww\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' 'C:\Users\Ruidoww\Desktop\预警分析平台推送对接\frontend\node_modules\vite\bin\vite.js' build
```

结果：

```text
success
```

注意：Vite build 仍有 chunk size warning，属于构建体积提示，不是构建失败。

浏览器验证：

```text
mock API 8080 + Vite 5173 已打开页面并完成截图验证
截图：C:\Users\Ruidoww\AppData\Local\Temp\edsp-schema-ingestion-plan-verification.png

真实后端 8080 + Vite 5173 已完成浏览器验证：
- POST /api/core/ingestion-plans/generate 生成 2 条推荐方案。
- GET /api/core/ingestion-plans?dataSourceId=1 返回结构化 plan_json。
- 页面“推荐接入方案”区域可展示字段映射、缺失字段、风险提示和推荐动作。
- 页面“模板类型”显示 `log_table`，不再误显示 `database_polling`。

轻量 Shadow Precheck 真实后端 + 浏览器验证：
- POST `/api/core/ingestion-plans/{id}/shadow-validate` 可对 `approved` 方案返回试运行前校验报告。
- API 验证确认 `standard_events` / `alert_decisions` / `alerts` 未被写入。
- Chrome CDP 验证页面存在“试运行前校验”按钮，点击后抽屉可展示校验结果、阻断项、标准事件预览和 `required_fields` 检查项。
```

格式检查：

```powershell
git diff --check
```

结果：

```text
exit 0
```

注意：PowerShell 输出提示 `HANDOFF.md`、`frontend/src/pages/SchemaPage.tsx` 和 `frontend/src/types.ts` 后续可能发生 `LF -> CRLF` 转换，这是 Git 换行提示，不是 diff 检查失败。

## 12. 已做代码审查

本阶段按要求使用子 agent 做了并行实现和代码审查。

已发现并修复的问题：

- `rejected` 方案曾会被 generate 复用，现改为保留历史并生成新方案。
- `transform_rule` 未进入 `plan_json`。
- generate 缺少事务保护。
- 前端曾发送非法状态 `discarded`。
- `rejected` 状态曾显示非法操作按钮。
- `fieldEvidence` 解析不完整。
- `draft` 状态过滤不合法。
- `HOST_NAME` 曾被误判为 `title`，现改为优先识别 `assetRef`。
- `plan_json.mode` 曾误写为模板类型，现固定为 `database_polling`。
- `USER_ID` 等主体 ID 曾可能被误用为 `externalId`，现排除。
- `LOGIN_NAME` 曾可能因 substring 命中 `log`，现改为 token 匹配。
- `generate()` 事务保护已补 Spring 代理级回滚测试。
- H2/JDBC 下 `plan_json` 曾通过 REST 变成 base64 字符串，现统一解析为结构化对象后返回。
- 前端模板类型曾在 `mode` 修正后误显示为 `database_polling`，现优先展示 `templateMatch.templateKey`。
- `shadow_ready` 前缺少轻量校验入口，现新增 Shadow Precheck API 和前端结果抽屉。

最后一轮复审指出的 `mode` 语义、主体 ID dedup、`login` 子串误判和事务回滚测试缺口已补回归测试并修复。真实后端联调发现的 `plan_json` 序列化和前端模板类型展示问题也已补修。后续“继续”阶段新增了轻量 Shadow Precheck，但仍只做试运行前校验，不进入正式规则引擎、真实落表或通知链路。

## 13. 当前残留风险

1. 前端 `SchemaPage.tsx` 已较大，后续如果继续扩展元数据能力，应考虑拆出推荐方案区域组件。
2. 目前仅是轻量 Shadow Precheck，尚未读取真实业务表样本行，也没有 shadow 结果落表。
3. 目前没有正式 `enabled` 状态，后续规则闭环阶段再引入。

## 14. 下一步建议

下一步不要直接进入规则引擎或通知推送。

建议顺序：

1. 检查本次 diff，确认没有无关文件。
2. 运行完整验证：

```powershell
cd backend
mvn -pl edsp-core -am test
cd ..
cd frontend
& 'C:\Users\Ruidoww\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' 'C:\Users\Ruidoww\Desktop\预警分析平台推送对接\frontend\node_modules\typescript\bin\tsc' --noEmit
& 'C:\Users\Ruidoww\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' 'C:\Users\Ruidoww\Desktop\预警分析平台推送对接\frontend\node_modules\vite\bin\vite.js' build
cd ..
git diff --check
```

3. 如需提交，提交当前阶段：

```powershell
git add backend/edsp-core/src/main/java/com/edsp/core/controller/IngestionPlanController.java `
        backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanGenerateRequest.java `
        backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanShadowValidationRequest.java `
        backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanStatusRequest.java `
        backend/edsp-core/src/main/java/com/edsp/core/service/IngestionPlanService.java `
        backend/edsp-core/src/main/java/com/edsp/core/service/SemanticProfilerService.java `
        backend/edsp-core/src/main/java/com/edsp/core/service/TemplateMatcherService.java `
        backend/edsp-core/src/test/java/com/edsp/core/service/IngestionPlanServiceTest.java `
        frontend/src/pages/SchemaPage.tsx `
        frontend/src/types.ts `
        HANDOFF.md

git commit -m "feat: add database intelligence ingestion plan MVP"
```

4. 下一个开发主题建议：

```text
refactor: split schema ingestion plan panel
```

如果继续扩展 Shadow Validator，需要先确认：

- 是否允许从真实业务数据源读取样本行。
- shadow 结果落表位置。
- shadow standard events 是否需要独立表或复用 raw/standard 结构。

## 15. 不要偏离的事项

- 不要把 IP-Guard 当成唯一目标。
- 不要绕过 raw 层直接生成 alerts。
- 不要要求客户逐字段填写底层表达式作为默认交付流程。
- 不要把 `shadow_ready` 当成正式启用。
- 不要让辅助表直接生成主接入方案。
- 不要覆盖人工确认过的字段映射。
- 不要在本阶段引入 AI / XGBoost / 复杂 Agent。
- 修改数据库结构必须走 Flyway migration。
- 修改接口后必须同步前端类型和页面调用。

## 16. 本机环境与启动交接

当前本机环境：

```text
JDK: C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
Maven: C:\Program Files\Apache\maven
Node.js: C:\Program Files\nodejs
Git: C:\Program Files\Git
Docker: 当前先不安装，本阶段使用 H2 本地库。
```

已处理：

- 已把 `C:\Program Files\Apache\maven\bin` 加入用户 PATH。
- `scripts/build-backend.ps1` 和 `scripts/start-local-backend.ps1` 已从硬编码路径改为自动识别 `JAVA_HOME` / `java.exe` / Maven 安装目录。
- 后端可使用 H2 PostgreSQL mode 本地库启动，不需要 Docker / PostgreSQL。
- 前端当前仍使用 Vite 开发服务，默认地址是 `http://localhost:5173`。

当前监听状态：

```text
8080  edsp-gateway
8081  edsp-auth
8082  edsp-core
8083  edsp-alert
8084  edsp-report
5173  frontend Vite dev server
```

后端启动命令：

```powershell
cd "C:\Users\Ruidoww\Desktop\预警分析平台推送对接"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local-backend.ps1
```

前端启动命令：

```powershell
cd "C:\Users\Ruidoww\Desktop\预警分析平台推送对接"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\dev-frontend.ps1
```

如果 PowerShell 执行 `npm -v` 报错：

```text
无法加载文件 C:\Program Files\nodejs\npm.ps1，因为在此系统上禁止运行脚本
```

说明 PowerShell 优先执行了 `npm.ps1`，但脚本执行策略禁止。可选处理：

```powershell
npm.cmd -v
npm.cmd install
npm.cmd run dev
```

或者允许当前用户运行本地脚本：

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

然后重新打开 PowerShell，再执行：

```powershell
npm -v
```

## 17. GitHub 推送交接

当前建议先不要 `git add .`，避免把 `target/`、`node_modules/`、`dist/` 等生成目录加入仓库。

推荐流程：

```powershell
cd "C:\Users\Ruidoww\Desktop\预警分析平台推送对接"
git status --short
git remote add origin https://github.com/<your-name>/<repo-name>.git
git branch -M main
```

如果 `origin` 已存在：

```powershell
git remote set-url origin https://github.com/<your-name>/<repo-name>.git
```

当前阶段推荐提交文件：

```powershell
git add HANDOFF.md `
        scripts/build-backend.ps1 `
        scripts/start-local-backend.ps1 `
        backend/edsp-core/src/main/java/com/edsp/core/controller/IngestionPlanController.java `
        backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanGenerateRequest.java `
        backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanShadowValidationRequest.java `
        backend/edsp-core/src/main/java/com/edsp/core/dto/IngestionPlanStatusRequest.java `
        backend/edsp-core/src/main/java/com/edsp/core/service/IngestionPlanService.java `
        backend/edsp-core/src/main/java/com/edsp/core/service/SemanticProfilerService.java `
        backend/edsp-core/src/main/java/com/edsp/core/service/TemplateMatcherService.java `
        backend/edsp-core/src/test/java/com/edsp/core/service/IngestionPlanServiceTest.java `
        backend/edsp-core/src/test/java/com/edsp/core/service/IngestionPlanServiceTransactionTest.java `
        frontend/src/pages/SchemaPage.tsx `
        frontend/src/types.ts

git commit -m "feat: add database intelligence ingestion plan MVP"
git push -u origin main
```
