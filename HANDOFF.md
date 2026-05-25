# 数据安全预警分析平台 Handoff

更新时间：2026-05-25
项目路径：`C:\Users\Ruidoww\Desktop\预警分析平台推送对接`

## 当前阶段状态

- 当前稳定分支：`master`
- 当前阶段：`GitHub Actions CI MVP`
- 最新 feature merge commit：`0cd67f1 merge: github actions ci mvp`
- 最新 HANDOFF docs commit：本次提交 `docs: update handoff for github actions ci mvp`
- 本轮阶段分支：`codex/github-actions-ci-mvp`
- 本轮结果：已完成 GitHub Actions CI MVP 收口；新增 GitHub Actions workflow，在 push / pull request 后自动执行后端测试、前端构建和 Docker Compose 构建校验。

## 已完成能力

- 新增 `.github/workflows/ci.yml`。
- CI 触发条件：
  - push 到 `master`
  - push 到 `codex/**`
  - pull request 到 `master`
  - 手动 `workflow_dispatch`
- 后端 CI job：
  - 使用 Ubuntu runner。
  - 使用 Temurin JDK 21。
  - 启用 Maven cache。
  - 执行 `mvn -B -pl edsp-alert -am test`。
  - 执行 `mvn -B -pl edsp-core -am test`。
- 前端 CI job：
  - 使用 Ubuntu runner。
  - 使用 Node.js 22。
  - 启用 npm cache。
  - 执行 `npm ci`。
  - 执行 `npm run build`。
- Docker Compose CI job：
  - 依赖后端测试和前端构建通过。
  - 执行 `docker compose config`。
  - 执行 `docker compose build`。
- CI 权限保持最小化：`permissions: contents: read`。
- CI 未使用 GitHub Secrets，未配置部署，未 push Docker image，未上传 artifact。

## 明确未做 / 禁止误解

- 未修改业务代码。
- 未修改后端代码。
- 未修改前端代码。
- 未修改 Dockerfile。
- 未修改 `docker-compose.yml`。
- 未修改 `AGENTS.md`。
- 未新增或修改 migration。
- 未新增 release / tag / package publish。
- 未配置部署。
- 未 push Docker image。
- 未上传 artifact。
- 未连接生产数据库。
- 未连接外部数据库。
- 未写入任何真实 token / password / key。
- 未改变当前开发流程和 merge 规则。

## 当前关键边界

- GitHub Actions CI 只负责验证，不负责部署。
- Workflow 内不得出现 secret 名称、真实数据库密码、企业微信 key、飞书 token、webhook token。
- Workflow 不得打印环境变量，不得执行 `cat .env`、`printenv`、`env` 等可能泄露环境的命令。
- 后续阶段仍必须遵守 staged MVP 流程：阶段分支、验证、review、明确批准后 merge、post-merge HANDOFF。
- Notification delivery 仍只允许从已有 `alerts` 出发。
- 手动通知入口仍为 `POST /api/notifications/alerts/send`，请求体仍只允许 `alertId + channelId`。
- Notification code 不得修改 alert lifecycle state。
- Alert lifecycle code 不得写 `notification_deliveries` 或调用 notification delivery services。

## 测试结果

- YAML 格式：`npx --yes js-yaml .github/workflows/ci.yml` 通过。
- 安全扫描：workflow 未命中 `secrets.*`、真实 token/password/key、部署、artifact、镜像 push、环境打印等禁止内容。
- Git 检查：阶段分支 `git diff --check` 通过，无 whitespace error。
- Git 状态：阶段分支已提交并 push 到 `origin/codex/github-actions-ci-mvp`。
- 本轮按计划未在本地执行完整 Maven / npm / Docker build；这些校验由 GitHub Actions 在 push / pull request 后执行。
- Post-merge Git 检查：docs 提交后执行并记录在最终回复。

## 已知后续项

- 首次 GitHub Actions 实际运行可能暴露 runner 环境、Docker Compose build 或 action major version 兼容问题，需要根据 GitHub CI 结果跟进。
- 当前 CI 不上传测试报告或构建 artifact；如后续需要，可单独规划 CI Reporting / Artifact MVP。
- 当前 CI 不做 PostgreSQL runtime smoke test；真实 PostgreSQL runtime 验证仍按 `docs/postgresql-runtime-verification.md` 文档流程执行。

## 下一轮建议

下一阶段建议回到业务主线，进入 `Notification Delivery Reliability MVP`。

建议范围：

- 继续保持手动通知入口和 `alertId + channelId` 边界。
- 在明确 migration 和状态模型后，为 `notification_deliveries` 增加结构化失败原因、可重放标记或最小重试记录。
- 不做自动通知，不做全量群发，不做告警升级，不做通知编排，不接入 AI / Kafka / Redis / ClickHouse。

备选方向：

- 如果优先完善工程质量，可先观察 GitHub Actions 首轮运行结果，并做最小 CI 兼容修复；仍不得扩大到部署、镜像发布或生产环境连接。
