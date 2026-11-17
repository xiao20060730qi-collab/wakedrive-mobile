# WakeDrive 疲劳驾驶检测 · iOS 版（SwiftUI）

与网页版、Android 版功能一致的 iOS 原生实现：**前端 SwiftUI**，后端继续使用同一套 Python（FastAPI）服务，四个模型全部在 iPhone 本地推理。

> ⚠️ 本工程在 Windows 上编写，**无法在 Windows 编译验证 iOS 应用**（需要 macOS + Xcode）。代码按 Xcode 16 / iOS 16+ 组织，首次在 Mac 上构建时若有个别 API 版本差异，按编译器提示微调即可（见文末排查）。

## 目录结构

```
ios/
├── project.yml                 # XcodeGen 工程描述（生成 .xcodeproj）
├── Podfile                     # MediaPipeTasksVision + onnxruntime-objc
└── WakeDriveMobile/
    ├── WakeDriveApp.swift      # App 入口 / 主题 / RootView
    ├── Info.plist
    ├── Net/ApiClient.swift     # 后端全部接口（登录/事件/报告/AI/休息点/管理员）
    ├── Vision/
    │   ├── OnnxRunner.swift    # ONNX Runtime iOS 封装
    │   ├── FaceAnalyzer.swift  # MediaPipe Face Landmarker（关键点/眨眼/张嘴）
    │   ├── Detectors.swift     # YOLO11n 手机 / YOLOv8-pose 姿态 / 6DRepNet 头部
    │   └── ImageUtils.swift    # 帧归一化 / letterbox / 6DRepNet 预处理 / 抓拍
    ├── Risk/RiskEngine.swift   # 滑动窗口判定 + 哈欠频率 + 报告录制
    ├── Alarm/AlarmManager.swift# 刺耳/常规/温和分级警报 + 中文语音
    ├── Camera/CameraController.swift # 前置摄像头取帧 + 预览
    ├── UI/MonitorViewModel.swift     # 四模型调度、风险、车速、上报
    ├── UI/MonitorView.swift          # 监测界面（预览/遮罩/校准/AI/工程模式/休息点）
    ├── UI/LoginView.swift            # 登录 / 注册 / 管理员登录入口
    ├── UI/ShellView.swift            # 底部八标签 + 工作台 + 模型页
    ├── UI/EventsView.swift           # 事件筛选/详情/抓拍/处理/导出/清空
    ├── UI/ReportsAndStats.swift      # 报告列表+风险曲线 / 统计
    ├── UI/AIAndSettings.swift        # AI 对话 / 设置页
    ├── UI/AdminView.swift            # 管理员后台（xiaoqi 专属管理员管理）
    └── Resources/models/             # 四个模型（face_landmarker.task + 3 个 ONNX）
```

## 没有 Mac、只有 Windows 怎么办（三种方案）

### 方案 A（推荐，零成本）：GitHub Actions 云构建 IPA + Windows 侧载

1. 把整个 `疲劳驾驶检测移动端` 文件夹推送到 GitHub 仓库（用 GitHub Desktop 即可，`.github/workflows/ios-build.yml` 已准备好）。
   - 注意 `.gitignore` 建议排除 `app/build`、`ios/build`、`.gradle`、模型原文件可保留（Action 需要）。
2. 打开仓库 **Actions → iOS Build (unsigned IPA) → Run workflow**，等待约 10 分钟。
3. 在运行记录底部下载产物 `WakeDriveMobile-unsigned-ipa`（内含 `WakeDriveMobile-unsigned.ipa`）。
4. Windows 上安装 [Sideloadly](https://sideloadly.io/)（或 AltStore），连接 iPhone：
   - 导入下载的 `.ipa`，输入你的 Apple ID（免费即可），Sideloadly 会用你的账号签名并安装。
   - iPhone 需在 **设置 → 隐私与安全性 → 开发者模式** 打开（iOS 16+），并信任描述文件。
5. 免费 Apple ID 签名的 App **7 天过期**，到期重新拖入 Sideloadly 重签；AltStore 可自动续签。购买 Apple Developer（$99/年）后可用 TestFlight 分发 90 天/版本。

> 说明：`macos-14` runner 为 GitHub 免费额度（公开仓库不限时长；私有仓库每月 2000 分钟，约够构建十几次）。

### 方案 B：临时租云 Mac（按小时计费，最接近真机调试）

- [MacinCloud](https://www.macincloud.com/)（按需 ~$1/小时）、[Scaleway Apple Silicon](https://www.scaleway.com/en/apple-silicon/)（约 €0.10/小时）等，远程桌面进入 macOS。
- 在云 Mac 上装 Xcode + `brew install xcodegen cocoapods`，按下方步骤构建，可直接连你的 iPhone（需 USB 重定向）或导出 IPA 回 Windows 侧载。

### 方案 C：先用网页版（今天就能在 iPhone 用）

网页版 https://wakedrive.xyz 已适配手机，Safari 打开后 **分享 → 添加到主屏幕**，即可以 App 图标全屏运行，包含监测、事件、报告、AI 等功能，无需任何构建。摄像头与本地模型推理在 Safari 中直接可用（受 iOS 内存限制，6DRepNet 加载较大，若卡顿可先用事件演示页）。

## 在 Mac 上构建

```bash
# 1. 安装工具（已装可跳过）
brew install xcodegen cocoapods

# 2. 生成工程并安装依赖
cd 疲劳驾驶检测移动端/ios
xcodegen generate
pod install

# 3. 打开 workspace（注意是 .xcworkspace 不是 .xcodeproj）
open WakeDriveMobile.xcworkspace
```

Xcode 中选择你的开发者 Team（Signing & Capabilities），连接 iPhone 运行即可。首启需要允许摄像头/定位权限。

## 四个模型（全部本地推理）

| 模型 | 运行方式 | 用途 | 事件 |
|---|---|---|---|
| MediaPipe Face Landmarker | MediaPipeTasksVision 0.10.14（CPU，逐帧） | 人脸/眼睛/口部 blendshape、EAR/MAR | `EYE_CLOSE`、`YAWN` |
| YOLO11n（ONNX） | ONNX Runtime iOS 1.22 | 手机检测（COCO 类 67） | `PHONE_USE` |
| YOLOv8n-pose（ONNX） | ONNX Runtime iOS | 通话姿态 / 喝水抬手（腕-耳、腕-口鼻） | `PHONE_USE`、`DRINK` |
| 6DRepNet360（ONNX） | ONNX Runtime iOS（224 BGR 归一化） | 头部 pitch/yaw | `HEAD_DOWN`、`HEAD_AWAY` |

推理节流与 Android/网页一致：人脸 300ms、手机 900ms、姿态 1200ms、头部 700ms，串行执行。

## 功能对照

登录/注册、管理员登录按钮（蓝紫渐变）、工作台（指标/最近事件/工程模拟演示）、实时监测（预览+人脸框+疲劳指数+指标+事件时间轴）、个人校准（10 秒采集阈值）、分级警报（刺耳/常规/温和）、人脸丢失 12s 提醒/40s 弹窗+循环警报、车速联动（>0.5m/s 自动开启且禁停、零速 3 分钟自动结束）、休息点推荐（高德导航跳转）、AI 解析、工程模式（疲劳/哈欠/手机/喝水/无人脸/重置）、事件筛选/详情/抓拍/标记处理/导出 CSV/清空、监测报告（结束自动保存 + 风险曲线详情）、统计分析（今日/7 天/30 天）、模型清单、系统设置（阈值/冷却/告警风格/测试警报/服务器地址）、管理员后台与 xiaoqi 专属管理员账号管理。

## 可能需要在 Mac 上微调的点

- `MediaPipeTasksVision` 与 `onnxruntime-objc` 的 Pod 版本如与最新 Podspec 冲突，按 `pod repo update` 后的可用版本调整 Podfile（代码使用的 API 在这两个库 0.10.x / 1.2x 版本内稳定）。
- `AVCaptureConnection.videoOrientation` 在 iOS 17+ 标记弃用（仅警告）。
- 首次构建若提示 `onnxruntime_objc` 模块名不同，检查 Pod 生成的 modulemap（通常模块名即 `onnxruntime_objc`）。
- 模型资源如未打进 Bundle，检查 XcodeGen 生成的 resources 阶段是否包含 `Resources/models`（本工程已在 project.yml 显式声明）。
- 真机性能：6DRepNet 模型 94MB，首次加载会慢几秒；建议 iPhone X 及以上机型。
