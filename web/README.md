# Q7VPN 网站（Flask）

账户注册/登录、图片验证码、IP 失败锁定、节点导入导出、安全中心。
SQLite 存储，gunicorn 运行。

## 本地跑起来

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

# 用 http 访问时必须关掉安全 Cookie，否则浏览器不回传会话，登录会"没反应"
COOKIE_SECURE=0 python3 app.py
```

打开 http://127.0.0.1:10000 。第一次启动会自动建库（q7vpn.sqlite3，与 app.py 同目录）。

## 生产部署

### 方式 A：Linux VPS 一条命令

```bash
cd web && sudo bash deploy_vps.sh
# 有域名：sudo DOMAIN=example.com bash deploy_vps.sh
```

脚本会：装依赖 → 建 venv → 装 requirements → 生成随机 SECRET_KEY/IP_HASH_SALT 写到
`/etc/q7vpn.env` → 注册 systemd 服务 `q7vpn` → 配 nginx 反代 → 跑健康检查。
数据库放在 `/var/lib/q7vpn/q7vpn.sqlite3`（脚本重跑不会覆盖已有数据）。

### 方式 B：Docker

```bash
docker compose up -d
```

别忘了先改 compose 里的 `SECRET_KEY` 和 `IP_HASH_SALT`。数据在 `q7vpn-data` 卷里。

### 方式 C：Render

- Build Command: `pip install -r requirements.txt`
- Start Command: `gunicorn app:app`
- Health Check Path: `/health`

必设环境变量：

| 变量 | 说明 |
|---|---|
| `SECRET_KEY` | 强随机串，至少 32 位。不设会自动生成并落盘到 `instance/secret_key`，但多实例部署必须显式指定 |
| `IP_HASH_SALT` | 独立的随机盐，用于给日志里的 IP 做不可逆标识 |
| `Q7VPN_DB_PATH` | 建议指向挂载磁盘，如 `/var/data/q7vpn.sqlite3`。**不指向持久磁盘，重启即丢库** |
| `RATELIMIT_STORAGE_URI` | 多实例时用 Redis URL，否则限流计数各实例各算 |
| `COOKIE_SECURE` | 走 https 用 `1`（默认）；纯 http 访问必须设 `0`，否则登录静默失败 |
| `Q7VPN_TZ_OFFSET` | 时间显示时区，默认 `8`（北京时间） |

## 健康检查

`GET /health` → `{"status":"ok","app":"Q7VPN","version":"5.2.1"}`

这个路由不受限流影响（平台探活很频繁，限流会把健康检查打成 429，服务会被判定不健康）。

## 安全机制现状

- 全局 CSRF 保护（Flask-WTF），令牌过期会中文提示并跳回
- 图片验证码，5 位、10 分钟有效、与会话绑定、可点击刷新
- 同一 IP 连续 5 次密码错误锁定 5 分钟（验证码输错不算，避免误锁真人）
- 密码 werkzeug PBKDF2 加盐哈希；安全问题答案 casefold 后哈希
- 登录/注册/找回/改密/导入/导出/删除都有独立限流
- 会话 Cookie：HttpOnly + SameSite=Lax + Secure（可配）
- 响应头：nosniff / X-Frame-Options / Referrer-Policy / Permissions-Policy

## 已知未实现

- 与 Android 端同步账户和节点的 API
- 短信验证与生产级找回密码（当前是安全问题方式）
- 多实例部署需要外置 Postgres + Redis（SQLite 已开 WAL，单机多 worker 没问题）

## 测试

仓库根 `tests/e2e.py`（34 项功能回归）和 `tests/load.py`（并发压测）。
跑法：先启动服务，再 `python tests/e2e.py`。
