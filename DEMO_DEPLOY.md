# EDSP Demo 云服务器部署

这套部署用于演示，不依赖真实客户数据库。启动后系统会自动生成 Demo 数据：

- 外部系统数据源：终端安全系统 Demo
- 告警：疑似敏感文件外发、移动存储拷贝异常、敏感数据高频访问
- 规则：文件外发、移动存储、敏感数据访问
- 通知通道：安全运营 Webhook Demo
- 报表：每日风险汇总 Demo
- 操作审计：心跳检测、告警采集、通知推送、报表创建

## 服务器要求

- Docker
- Docker Compose plugin
- 建议 2C4G 以上
- 如果直接用服务器 IP 访问，开放安全组端口：`3000`
- 如果前面接 1Panel / Lucky / Nginx，只需要对外开放代理入口端口，例如 `443`

## 上传代码

把整个项目目录上传到云服务器，例如：

```bash
/opt/edsp-demo
```

进入目录：

```bash
cd /opt/edsp-demo
```

## 配置环境变量

```bash
cp .env.example .env
vi .env
```

至少修改数据库密码：

```env
POSTGRES_PASSWORD=your-strong-password
FRONTEND_PORT=3000
EDSP_DEMO_ENABLED=true
```

## 启动

```bash
docker compose up -d --build
```

或者：

```bash
bash scripts/deploy-demo.sh
```

如果前面使用默认 `.env.example`，访问：

```text
http://你的服务器IP:3000
```

如果服务器有 80/443，或者前面还有 1Panel/Lucky 反向代理，可以把代理目标设置为：

```text
http://127.0.0.1:3000
```

## 查看状态

```bash
docker compose ps
docker compose logs -f edsp-core
docker compose logs -f frontend
```

## 重启

```bash
docker compose restart
```

## 更新代码后重新部署

```bash
docker compose up -d --build
```

## 清空 Demo 数据重新来

这会删除 PostgreSQL 数据卷，谨慎使用：

```bash
docker compose down -v
docker compose up -d --build
```

## 演示路线

1. 打开总览，看风险态势、数据源健康、开放告警。
2. 进入外部接入，点击心跳检测，展示外部系统可连接。
3. 点击立即采集，展示采集到标准告警。
4. 进入告警中心，讲解告警来源、用户、资产、策略和状态。
5. 进入通知中心，展示 Webhook 通知通道。
6. 进入报表中心，展示 Demo 报表和下载入口。
7. 右上角 admin -> 操作审计，展示平台留痕。

## 注意

Demo 模式只用于演示。如果要接入真实客户数据库，需要把 `EDSP_DEMO_ENABLED=false`，并确保服务器网络能访问客户内网数据库。
