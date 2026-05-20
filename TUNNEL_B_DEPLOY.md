# 方案 B：国外服务器 443 入口 + 家里服务器运行 EDSP

目标：

```text
用户浏览器
  -> https://demo.your-domain.com
  -> 国外服务器 Lucky / 443
  -> Lucky 隧道
  -> 家里服务器 EDSP / 127.0.0.1:3000
```

## 1. 家里服务器部署 EDSP

上传 `edsp-demo-deploy.tar.gz` 到家里服务器，例如：

```bash
mkdir -p /opt/edsp-demo
cd /opt/edsp-demo
tar -xzf edsp-demo-deploy.tar.gz
cp .env.example .env
vi .env
```

建议 `.env`：

```env
POSTGRES_DB=edsp
POSTGRES_USER=edsp
POSTGRES_PASSWORD=change-to-your-strong-password
FRONTEND_PORT=3000
EDSP_DEMO_ENABLED=true
```

启动：

```bash
docker compose up -d --build
docker compose ps
```

本机验证：

```bash
curl -I http://127.0.0.1:3000
```

能返回 `200` 或 `index.html` 即可。

### 1Panel 部署方式

如果家里服务器已经安装 1Panel 和 Docker，建议直接用 1Panel 管理这套服务：

1. 在 1Panel 文件管理里创建目录：

```text
/opt/edsp-demo
```

2. 上传 `edsp-demo-deploy.tar.gz` 到 `/opt/edsp-demo`，然后在 1Panel 终端执行：

```bash
cd /opt/edsp-demo
tar -xzf edsp-demo-deploy.tar.gz
cp .env.example .env
```

3. 编辑 `/opt/edsp-demo/.env`：

```env
POSTGRES_DB=edsp
POSTGRES_USER=edsp
POSTGRES_PASSWORD=change-to-your-strong-password
FRONTEND_PORT=3000
EDSP_DEMO_ENABLED=true
```

4. 进入 1Panel：

```text
容器 -> 编排 / Compose -> 创建编排
```

关键配置：

```text
名称：edsp-demo
路径：/opt/edsp-demo
Compose 文件：/opt/edsp-demo/docker-compose.yml
环境变量文件：/opt/edsp-demo/.env
```

5. 启动编排后，在 1Panel 里确认这些容器为运行中：

```text
edsp-postgres
edsp-auth
edsp-core
edsp-alert
edsp-report
edsp-gateway
edsp-frontend
```

6. 在家里服务器验证：

```bash
curl -I http://127.0.0.1:3000
```

如果 1Panel 和 EDSP 在同一台机器，Lucky 的本地目标仍然填：

```text
http://127.0.0.1:3000
```

## 2. Lucky 隧道配置

具体菜单名称以你的 Lucky 版本为准，核心参数如下。

家里服务器 Lucky 客户端：

```text
本地目标：http://127.0.0.1:3000
连接到：国外服务器 Lucky 隧道节点
```

国外服务器 Lucky 节点：

```text
监听入口：443
域名：demo.your-domain.com
转发目标：Lucky 隧道内的 EDSP 服务
```

如果 Lucky 需要一个隧道服务端口，例如 `16666`，则：

```text
国外服务器安全组开放：443、16666
家里服务器只需要能主动连到国外服务器 16666
```

EDSP 的 `3000` 不需要对公网开放。

## 3. 域名解析

把域名解析到国外服务器固定 IP：

```text
demo.your-domain.com -> 国外服务器公网 IP
```

## 4. HTTPS

国外服务器有 443，可以在 Lucky 上给 `demo.your-domain.com` 配证书。

如果没有 80，证书申请使用 DNS 验证；如果有 80，可以用 HTTP 验证。

## 5. 访问

```text
https://demo.your-domain.com
```

## 6. 故障定位

家里服务器检查 EDSP：

```bash
docker compose ps
curl -I http://127.0.0.1:3000
```

国外服务器检查入口：

```bash
curl -I https://demo.your-domain.com
```

如果国外入口正常但页面打不开，优先检查 Lucky 隧道是否在线、家里服务器是否能主动连接国外节点。
