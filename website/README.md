# WakeDrive 官网前端（受保护构建 · Protected Build）

本目录是 `wakedrive.xyz` 当前线上版本（2026-11-23）的**保护构建产物**：代码已做混淆加密与反调试/防复制处理，不包含可直接维护的明文源码。

## 目录结构

```
website/
├── index.html          官网首页
├── styles.css / script.js
├── assets/             首页图片与图标
├── frontend/           网页工作台（登录/注册/监测/模型/事件/报告/统计/AI/设置/管理员）
│   ├── js/             已混淆的业务脚本 + guard.js（前端防护）
│   ├── css/
│   ├── models/         浏览器端推理模型（ONNX / JSON）
│   └── vendor/         OpenCV.js / WASM、QRCode 等第三方库（保持原样）
└── README.md
```

后端 API 见仓库根目录 `backend/`（FastAPI），部署时 nginx 将 `/api` 反向代理到后端服务。

## 已实施的前端保护

1. **JS 代码混淆加密**：全部业务脚本经过字符串数组加密（Base64）、控制流平坦化、十六进制标识符重命名、自防御代码保护，源码不可读、不可直接复制复用。
2. **域名锁**：只在 `wakedrive.xyz` / `www.wakedrive.xyz` / `localhost` / `127.0.0.1` 下渲染，其他域名显示 403 访问控制页。
3. **防调试**：检测开发者工具（窗口尺寸差 + toString 探针）、`debugger` 探针、拦截 F12 / Ctrl+Shift+I/J/C / Ctrl+U / Ctrl+S / Ctrl+P。
4. **防复制**：禁用右键菜单、文本选中、复制/剪切、图片拖拽。
5. **版权水印**：右下角版权角标 + 全页平铺半透明水印，删除后自动恢复；另注册 copyright meta。
6. **防嵌套**：检测 iframe 并跳出。

> 说明：前端代码混淆可以显著提高逆向与抄袭成本，但无法做到「绝对不可破解」——浏览器必须执行代码，理论上仍可被专业手段还原，请勿在代码中放置任何密钥或敏感凭证（本项目已核对：前端无敏感凭证）。

## 本地预览

```bash
cd website
python -m http.server 8080
# 打开 http://localhost:8080/
```

本地预览时后端接口不可用（页面静态渲染正常），如需完整功能请先启动 `backend/`。

## 部署

将本目录全部文件上传到站点根目录（生产环境 `/www/wwwroot/wakedrive.xyz`：
`index.html`、`styles.css`、`script.js`、`assets/`、`frontend/` 对应站点根目录结构），
nginx 静态托管 + `/api` 反代到 FastAPI 后端即可。

**注意**：这是混淆后的产物，日常维护请基于私有明文源码重新构建，不要直接修改这些文件（混淆代码修改后可能触发自防御保护而失效）。
