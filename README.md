# WakeDrive 疲劳驾驶检测 · 全栈（Android Kotlin + iOS SwiftUI + 网页端 + Python 后端）

> 仓库包含四部分：Android 原生端 [`app/`](app)、iOS 端 [`ios/`](ios/README.md)、网页端 [`website/`](website/README.md)（线上 <https://wakedrive.xyz>）、Python FastAPI 后端 [`backend/`](backend)。三端共用同一套账号体系、事件与报告接口。

> iOS 需要 macOS + Xcode 构建，Windows 无法编译验证；iOS 工程使用 SwiftUI + MediaPipe Tasks + ONNX Runtime iOS，功能与 Android / 网页端对齐。

Android 端（`app/`）是 WakeDrive 的原生重写版：**前端 Kotlin（CameraX + 原生 Views）**，**后端继续使用 Python（FastAPI）**，四个检测模型全部在手机本地推理，监测事件（含抓拍图）通过账号会话上传到后端，管理员后台可直接查看。

## 目录结构

```
wakedrive-mobile/
├── app/
│   ├── build.gradle.kts                  # AGP 9.4 / Kotlin 2.3 / compileSdk 37 / minSdk 26
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/models/                # 四个模型文件（全部参与推理）
│       │   ├── face_landmarker.task      # MediaPipe Face Landmarker 3.8MB
│       │   ├── yolo11n.onnx              # 手机检测 YOLO11n 10.9MB
│       │   ├── yolov8n-pose.onnx         # 姿态/通话/抬手 YOLOv8n-pose 13.5MB
│       │   └── 6drepnet360.onnx          # 头部姿态 6DRepNet360 94MB
│       ├── java/com/wakedrive/mobile/
│       │   ├── App.kt                    # ApiClient 初始化
│       │   ├── alarm/AlarmController.kt  # TTS 中文语音 + ToneGenerator 分级警报
│       │   ├── net/ApiClient.kt          # OkHttp + 持久化 Cookie 会话
│       │   ├── risk/TemporalRiskTracker.kt  # 时间窗闭眼/哈欠/占比判定
│       │   ├── risk/RiskCoordinator.kt   # 事件冷却 + 3 分钟 ≥2 次哈欠升级
│       │   ├── speed/SpeedMonitor.kt     # GPS 车速联动
│       │   ├── util/BitmapUtils.kt       # 帧转换 / letterbox / 6DRepNet 预处理 / 抓拍
│       │   ├── vision/                   # 四个模型的 Kotlin 推理封装
│       │   │   ├── FaceAnalyzer.kt       # MediaPipe 人脸 + 眨眼/张嘴 blendshape
│       │   │   ├── Detectors.kt          # YOLO11n / YOLOv8-pose / 6DRepNet
│       │   │   └── OnnxRunner.kt         # ONNX Runtime 会话（模型落盘到 filesDir）
│       │   └── ui/                       # Login / Monitor / Events / 遮罩与列表
│       └── res/                          # 深色主题界面
├── backend/                              # Python 后端源码（Android / iOS / 网页端共用）
├── website/                              # 网页端（wakedrive.xyz 线上版本 · 受保护构建，见 website/README.md）
├── ios/                                  # iOS SwiftUI 工程（见 ios/README.md）
├── .github/workflows/                    # iOS 构建流水线（GitHub Actions）
└── gradle/                               # Gradle wrapper 配置（9.6.0）
```

## 网页端（website/）

线上站点：<https://wakedrive.xyz>（阿里云服务器 nginx + FastAPI，站点根目录 `/www/wwwroot/wakedrive.xyz`）。

`website/` 是线上当前版本（2026-11-23）的**保护构建产物**，共 14 个页面：官网首页 + 网页工作台（登录 / 注册、实时监测、模型清单、事件、报告、统计、AI 解析、设置、管理员后台），以及四个浏览器端推理模型与 OpenCV 运行库。

前端保护措施：

- 业务 JS 全部混淆加密（字符串数组 Base64 编码 + 控制流平坦化 + 十六进制标识符 + 自防御），不可直接阅读或复用；
- `frontend/js/guard.js`：域名锁（仅 `wakedrive.xyz` / `www.wakedrive.xyz` / `localhost` / `127.0.0.1` 可渲染，其他域名显示 403）、开发者工具检测、F12 与查看源码/保存/打印快捷键拦截、防右键/防选中/防复制、动态版权水印、iframe 防嵌套；
- 公开仓库中不包含网页端明文源码；混淆用于提高抄袭与逆向成本，浏览器仍需执行代码，并非绝对不可破解。

本地预览：

```bash
cd website
python -m http.server 8080      # 打开 http://localhost:8080/
```

完整功能需同时启动 `backend/`（`frontend/js/config.js` 在 localhost 下会自动把 API 指向 `http://localhost:8000/api`）。部署时把 `website/` 内容放到 nginx 站点根目录、`/api` 反向代理到 FastAPI 服务即可，详见 [`website/README.md`](website/README.md)。

## 构建

本机环境（已验证可用）：

- JDK：`D:\java`（Java 26，`gradle.properties` 中已固定 `org.gradle.java.home`）
- Android SDK：`D:\Android\Sdk`（platform android-37，build-tools 36/37）
- Gradle：9.6.0（已缓存在 `~/.gradle/wrapper/dists`），AGP 9.4.0

```bash
cd D:\疲劳驾驶检测移动端
gradle assembleDebug
# 或使用缓存发行版：
C:\Users\xiaoqi\.gradle\wrapper\dists\gradle-9.6.0-bin\<hash>\gradle-9.6.0\bin\gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 首次构建需要联网下载依赖（CameraX、MediaPipe Tasks、ONNX Runtime、OkHttp）。
> APK 体积约 130MB，主要来自 6DRepNet360（94MB）。如需减小体积可在打包时裁剪 ABI。

## 运行

1. 安装 APK（`adb install -r app-debug.apk`）。
2. 打开 App → 登录已注册的驾驶账号（默认后端 `https://wakedrive.xyz/api`）。
   - 长按登录页底部“服务器：…”可切换后端地址（例如局域网 `http://192.168.x.x:8000/api`）。
3. 点“开启监测”，允许摄像头权限（车速联动还需要定位权限）。
4. 手机屏幕会显示实时疲劳指数、EAR、闭眼时长、哈欠分数与本次告警数；告警事件自动上传当前账号，可在“事件”页查看，也可在网页管理员后台查看抓拍图片。

### 本地后端

```bash
cd backend
pip install -r requirements.txt
python -m uvicorn app:app --host 0.0.0.0 --port 8000
```

数据库默认 `backend/wakedrive.db`（SQLite）。App 端把服务器地址改成 `http://<电脑局域网IP>:8000/api` 即可联调；Android 9+ 明文 HTTP 已在 Manifest 中放开（`usesCleartextTraffic`）。

## 四个模型的调用方式（全部本地推理）

| 模型 | 运行方式 | 用途 | 触发事件 |
|---|---|---|---|
| MediaPipe Face Landmarker | `tasks-vision` 0.10.14，CPU，IMAGE 模式逐帧 | 人脸关键点、EAR、MAR、眨眼/张嘴 blendshape | `EYE_CLOSE`、`YAWN` |
| YOLO11n（ONNX） | ONNX Runtime Android 1.22，640 输入，COCO 类 67=手机 | 手机可见性、手机贴近人脸 | `PHONE_USE` |
| YOLOv8n-pose（ONNX） | ONNX Runtime，640 输入，17 关键点 | 手腕-耳朵（通话）、手腕-口鼻（喝水/抬手） | `PHONE_USE`、`DRINK` |
| 6DRepNet360（ONNX） | ONNX Runtime，224 BGR + ImageNet 归一化 | 头部 pitch/yaw（低头/视线偏离） | `HEAD_DOWN`、`HEAD_AWAY` |

推理节流（与网页版一致）：人脸 300ms、手机检测 900ms、姿态 1200ms、头部姿态 700ms，全部在单线程执行器上串行执行，避免手机发热和会话冲突。

## 风险规则与警报（对齐网页版）

- **眼镜误判缓解**：EAR 低于阈值时还需眨眼 blendshape ≥ 0.35 才计为闭眼
- **持续闭眼 / 持续张嘴**：按帧阈值换算时长，分级轻度/中度/重度；近 60 秒闭眼占比同样参与评级
- **哈欠频率**：3 分钟内第 2 次哈欠起升级为中度并加快提醒
- **人脸丢失**：12 秒温和语音提醒（30 秒内不重复）；40 秒触发 `FACE_LOST`：弹窗 + 刺耳双音循环 + 语音“长时间未检测到人脸”，恢复人脸自动停止
- **分级警报**：重度疲劳/手机分心/长时间无人脸 → 高频刺耳双音（700~900ms 循环）；中度 → 常规提示音；喝水 → 温和单音 + “请把双手放回方向盘”
- **车速联动**：GPS 速度 > 0.5 m/s 自动开启监测且行驶中禁止手动关闭；速度为零持续 3 分钟自动结束监测
- **抓拍与上报**：每个事件附带 320px JPEG 抓拍（`data:image/jpeg;base64`），后端 `EventCreate.snapshot` 校验后存储，管理员端可直接查看

## 功能对照（与网页版一致）

| 网页功能 | 手机端实现 |
|---|---|
| 登录 / 注册 | LoginActivity / RegisterActivity（同样的账号体系与 Cookie 会话） |
| 工作台 | DashboardFragment：今日事件、待处理、重度计数、最近事件、工程模拟演示卡片 |
| 实时监测 | MonitorActivity：CameraX + 四模型、疲劳指数/EAR/闭眼时长/哈欠、事件时间轴、抓拍上报 |
| 个人校准 | 监测页「个人校准」：采集 10 秒正常状态，中位数推算 EAR/MAR 阈值并保存账号 |
| 警报分级 | AlarmController：刺耳双音（重度/分心/人脸丢失）、常规提示、喝水温和语音 |
| 人脸丢失 | 12 秒温和提醒、40 秒弹窗 + 刺耳循环 + FACE_LOST 事件 |
| 车速联动 | SpeedMonitor：>0.5m/s 自动开启且行驶中禁停，零速 3 分钟自动结束 |
| 休息点推荐 | 重度疲劳时按定位查询 /rest-stops，弹窗列出并跳转高德导航 |
| AI 解析窗口 | 监测页「✦ AI 解析」+ AI 页聊天（/ai/chat，失败时本地降级） |
| 工程模式 | 监测页「⚙ 工程模式」8 项模拟 + 工作台一键演示入口 |
| 事件记录 | EventsFragment：等级/类型筛选、详情（含抓拍图）、标记已处理、导出 CSV、清空 |
| 监测报告 | 结束监测自动生成并 POST /reports；ReportsFragment 列表 + 风险曲线 + 事件详情 |
| 统计分析 | StatsFragment：今日/7 天/30 天，等级与类型分布、7 天趋势 |
| 模型清单 | ModelsFragment：4 个已接入模型 + 2 个待接入说明 |
| 系统设置 | SettingsFragment：驾驶员/车牌、阈值与帧数、冷却、告警开关与风格、测试警报、服务器地址 |
| 管理员后台 | AdminActivity：管理员登录、账户列表、事件与抓拍、删除司机账号 |
| 管理员账号管理 | 仅 xiaoqi：新增管理员 / 重置密码 / 删除管理员（后端同样校验） |

## 视觉风格

界面沿用网页版的深色控制台风格：`#06121D` 深底、`#0D2130` 圆角卡片（`#254557` 描边）、青色主色 `#42D9F5`。
主按钮为青色渐变胶囊，次按钮深蓝描边，危险操作为暗红描边；底部导航带图标 + 文字，选中项显示青色药丸背景。
登录/注册输入框为圆角深色描边样式，与网页端保持一致。
（截图：`ui-login.png` / `ui-dashboard.png` / `ui-monitor.png` 由模拟器实拍）

## 构建验证记录

- `gradle assembleDebug` 构建成功（AGP 9.4 内置 Kotlin；中文目录已通过 `android.overridePathCheck=true` 放行）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，约 242MB（debug 全 ABI，模型资源占主要体积）；根目录另存一份 `WakeDrive-Mobile-debug.apk`。
- 模拟器冒烟测试（x86_64 API 35，虚拟摄像头）：
  - 登录 / 工作台 / 事件（5 条含筛选）/ 报告 / 统计（真实分布）/ AI 对话（DeepSeek 在线回答）/ 设置 / 模型 页面全部正常；
  - 监测页：工程模式模拟重度疲劳成功写入事件并更新时间轴与工作台计数；
  - 摄像头：无前置设备自动回退后置；虚拟摄像头可开始/停止监测；
  - YOLO11n 与 YOLOv8-pose 在相机帧上真实推理（日志 `WakeDriveVision` 计数），无崩溃；
  - 系统栏 Insets 已适配（修复底部标签被手势导航条遮挡）；
  - MediaPipe 原生库不含 x86_64，模拟器上人脸模型自动降级提示，arm64 真机完整运行四模型。
- 待真机验证：四模型完整联动的检测准确率与实时性能；管理员后台的人工登录流程建议在真机再走一遍。

## 与网页版的功能差异 / 已知限制

- AI 回复中的 Markdown 加粗标记（**）在手机端以原文显示，仅影响观感。
- 休息点推荐跳转高德导航链接，未内嵌地图控件。
- 个人校准采用 10 秒样本中位数推算阈值，未包含网页版的全部稳定性校验。
- MediaPipe 使用 CPU 代理（兼容性优先）；6DRepNet 会话初始化需要复制 94MB 模型到应用私有目录，首次启动会多花几秒。
- 警报音色由 `ToneGenerator` 提供，不同厂商 ROM 的音色/音量存在差异；语音依赖系统中文 TTS。
- 本机已验证工程可编译（见构建结果），但摄像头 + 四模型联动的真机行为需要在手机上实测（模拟器通常无摄像头或性能不足）。
