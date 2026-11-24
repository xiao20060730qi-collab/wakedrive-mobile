# WakeDrive 后端服务（Python · FastAPI）

> WakeDrive 的统一后端：为网页端、Android 与 iOS 提供账号会话、驾驶事件、监测报告、统计分析、AI 驾驶助手与休息点查询等接口。默认使用 SQLite，也可切换到 MySQL。

## 技术栈

| 组件 | 说明 |
|---|---|
| Web 框架 | FastAPI + Uvicorn |
| 数据存储 | SQLite（默认）/ MySQL（可选） |
| 会话 | 服务端签发的 Cookie 会话（HttpOnly） |
| AI | DeepSeek Chat Completions（可选，未配置时前端自动降级） |
| 定位服务 | 高德地图服务（休息点 / 地图配置，可选） |

## 目录结构

```text
backend/
├── app.py               # FastAPI 应用入口：中间件、路由挂载、健康检查
├── auth.py              # 账号注册 / 登录 / 会话校验（SQLite 实现）
├── mysql_auth.py        # MySQL 版账号与会话实现（可选）
├── account_data.py      # 账号数据、迁移与通用数据访问
├── database.py          # 数据库引擎选择与连接配置
├── reports.py           # 监测报告接口
├── risk_scoring.py      # 风险评分与等级换算
├── wakedrive.sql        # MySQL 初始化脚本（建库建表）
├── requirements.txt     # Python 依赖
└── README.md
```

## 本地运行

```bash
cd backend
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python -m uvicorn app:app --host 0.0.0.0 --port 8000
```

启动后访问 `http://localhost:8000/api/health` 应返回 `{"status": "ok", ...}`。

- 默认使用 SQLite：首次启动自动在 `backend/wakedrive.db` 创建**空库**；
- 打开网页端（`http://localhost:8080/` 或线上站点）注册账号即可开始使用；
- 网页端在 `localhost` 下会自动把接口地址指向 `http://localhost:8000/api`。

## 环境变量

| 变量 | 说明 | 默认值 |
|---|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek API Key，用于 AI 驾驶助手；未配置时 AI 接口返回不可用，前端自动降级 | 空 |
| `WAKEDRIVE_DB_ENGINE` | 数据库引擎：`sqlite` / `mysql` / `auto` | `sqlite` |
| `WAKEDRIVE_DB_PATH` | SQLite 数据库文件路径 | `backend/wakedrive.db` |
| `WAKEDRIVE_MYSQL_HOST` | MySQL 主机 | — |
| `WAKEDRIVE_MYSQL_PORT` | MySQL 端口 | `3306` |
| `WAKEDRIVE_MYSQL_USER` | MySQL 用户名 | — |
| `WAKEDRIVE_MYSQL_PASSWORD` | MySQL 密码 | — |
| `WAKEDRIVE_MYSQL_DATABASE` | MySQL 数据库名 | — |
| `WAKEDRIVE_COOKIE_SECURE` | 是否仅通过 HTTPS 发送会话 Cookie（线上建议 `1`） | 按协议自动判断 |
| `WAKEDRIVE_ALLOW_DEMO_LOGIN` | 是否允许演示账号快速登录（仅本地调试建议开启） | 关闭 |

> 生产环境请通过环境变量或受保护的环境文件（如 `/etc/wakedrive.env`，权限 `0600`）注入密钥，**不要**把密钥写入代码、前端或仓库。

## 数据存储

### SQLite（默认）

- 零配置，适合本地开发与单机部署；
- 数据文件默认位于 `backend/wakedrive.db`，首次启动自动建表；
- 备份时直接复制数据库文件（建议停机或使用 SQLite 备份命令）。

### MySQL（可选）

```bash
mysql -u root -p < backend/wakedrive.sql
```

然后在环境变量中配置 `WAKEDRIVE_DB_ENGINE=mysql` 与 `WAKEDRIVE_MYSQL_*` 连接信息，重启服务即可。

## 接口分组

所有接口统一以 `/api` 为前缀，返回 JSON；除登录 / 注册 / 健康检查外，其余接口均需要有效会话。

| 分组 | 路径前缀 | 说明 |
|---|---|---|
| 健康检查 | `/api/health` | 服务状态、数据库模式与模型配置概览 |
| 账号会话 | `/api/auth/*` | 注册、登录、退出、当前会话校验 |
| 驾驶事件 | `/api/events*` | 事件创建（含抓拍快照）、列表、筛选、标记处理、删除 |
| 系统设置 | `/api/settings` | 阈值、冷却、告警风格、语音包、驾驶员信息等按账号保存 |
| 监测报告 | `/api/reports*` | 报告保存、列表、详情与风险曲线数据 |
| 统计分析 | `/api/analysis*` | 今日 / 7 天 / 30 天等级与类型分布、趋势 |
| AI 助手 | `/api/ai/*` | AI 问答（DeepSeek），未配置密钥时返回不可用 |
| 休息点 | `/api/rest-stops` | 按经纬度查询附近休息点 |
| 地图配置 | `/api/map-config` | 前端地图 SDK 所需配置下发 |

示例：

```bash
curl -fsS http://localhost:8000/api/health
curl -fsS -X POST http://localhost:8000/api/auth/login \
  -H 'Content-Type: application/json' \
  -c cookies.txt \
  -d '{"username":"your-name","password":"your-password"}'
```

## 部署

推荐使用 systemd 常驻 Uvicorn，并由 nginx 反向代理 `/api`：

```ini
# /etc/systemd/system/wakedrive.service
[Unit]
Description=WakeDrive backend
After=network.target

[Service]
WorkingDirectory=/opt/wakedrive/backend
EnvironmentFile=-/etc/wakedrive.env
ExecStart=/opt/wakedrive/.venv/bin/python -m uvicorn app:app --host 127.0.0.1 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now wakedrive.service
curl -fsS https://wakedrive.xyz/api/health
```

nginx 侧只需把 `/api/` 反向代理到 `127.0.0.1:8000/api/`，同时转发 `Host`、`X-Real-IP` 与 `X-Forwarded-Proto` 头；静态站点配置见 [`website/README.md`](../website/README.md)。

## 安全与运维建议

- 生产数据库与密钥不进入代码仓库；`.env` / 环境文件权限设为 `0600`；
- 开启 HTTPS 并设置 `WAKEDRIVE_COOKIE_SECURE=1`，会话 Cookie 使用 `HttpOnly`；
- 建议对 `/api/auth/*` 增加请求频率限制，防止撞库；
- 定期备份数据库文件（SQLite）或使用 MySQL 备份策略；
- 发布前先执行 `curl -fsS https://<域名>/api/health` 做健康检查，再验证一条登录 / 事件读写的完整链路；
- 对接第三方服务（DeepSeek、高德）的密钥只在服务端环境变量中保存，前端仅调用自家接口。
