# WakeDrive iOS 客户端（SwiftUI）

> 与 Android 端、网页端功能一致的 iOS 原生实现：SwiftUI 界面 + MediaPipe Tasks + ONNX Runtime，四个检测模型全部在设备本地推理；账号、事件、报告与统计数据与其它端共用同一套后端服务。

## 环境要求

| 项目 | 要求 |
|---|---|
| 构建环境 | macOS + Xcode 16（iOS 16.0+ 部署目标） |
| 生成工具 | [XcodeGen](https://github.com/yonaskolb/XcodeGen)、CocoaPods |
| 依赖库 | `MediaPipeTasksVision`（0.10.x）、`onnxruntime-objc`（1.2x） |
| 设备建议 | iPhone X 及以上（6DRepNet 模型 94 MB，首次加载较慢） |

> 本工程主要在 Windows 环境编写，Windows 上无法编译 iOS 应用。首次在 Mac 上构建时若遇到个别 API 版本差异，按编译器提示微调即可，见文末「常见问题」。

## 工程结构

```text
ios/
├── project.yml                     # XcodeGen 工程描述（生成 .xcodeproj）
├── Podfile                         # MediaPipeTasksVision + onnxruntime-objc
└── WakeDriveMobile/
    ├── WakeDriveApp.swift          # App 入口 / 主题 / RootView
    ├── Info.plist
    ├── Net/ApiClient.swift         # 后端接口封装（登录、事件、报告、AI、休息点等）
    ├── Vision/
    │   ├── OnnxRunner.swift        # ONNX Runtime iOS 封装
    │   ├── FaceAnalyzer.swift      # MediaPipe Face Landmarker（关键点 / 眨眼 / 张嘴）
    │   ├── Detectors.swift         # YOLO11n 手机检测 / YOLOv8-pose 姿态 / 6DRepNet 头部姿态
    │   └── ImageUtils.swift        # 帧归一化 / letterbox / 6DRepNet 预处理 / 抓拍
    ├── Risk/RiskEngine.swift       # 滑动窗口判定 + 哈欠频率 + 报告录制
    ├── Alarm/AlarmManager.swift    # 分级提示音 + 中文语音提醒
    ├── Camera/CameraController.swift  # 前置摄像头取帧与预览
    ├── UI/
    │   ├── MonitorViewModel.swift  # 四模型调度、风险计算、车速联动、事件上报
    │   ├── MonitorView.swift       # 实时监测界面（预览 / 遮罩 / 校准 / AI / 工程模式）
    │   ├── LoginView.swift         # 登录 / 注册
    │   ├── ShellView.swift         # 底部标签、工作台、模型清单
    │   ├── EventsView.swift        # 事件筛选 / 详情 / 抓拍 / 处理 / 导出 / 清空
    │   ├── ReportsAndStats.swift   # 监测报告 + 风险曲线 / 统计分析
    │   └── AIAndSettings.swift     # AI 对话 / 系统设置
    └── Resources/models/           # face_landmarker.task + 3 个 ONNX 模型
```

## 构建方式

### 方式一：Mac 本地构建（推荐用于真机调试）

```bash
# 1. 安装工具（已安装可跳过）
brew install xcodegen cocoapods

# 2. 生成 Xcode 工程并安装依赖
cd ios
xcodegen generate
pod install

# 3. 打开 workspace（注意是 .xcworkspace，不是 .xcodeproj）
open WakeDriveMobile.xcworkspace
```

在 Xcode 中配置开发者 Team（Signing & Capabilities），连接 iPhone 运行。首次启动需要允许摄像头与定位权限。

### 方式二：GitHub Actions 云构建 IPA（Windows 环境可用）

仓库内置工作流 `.github/workflows/ios-build.yml`，可在没有 Mac 的情况下构建未签名 IPA，再通过侧载工具安装到 iPhone。

1. 将代码推送到 GitHub 仓库；
2. 打开仓库 **Actions → iOS Build (unsigned IPA) → Run workflow**，等待约 10 分钟；
3. 在该次运行记录底部下载产物 `WakeDriveMobile-unsigned-ipa`（内含 `WakeDriveMobile-unsigned.ipa`）；
4. Windows 上安装 [Sideloadly](https://sideloadly.io/)（或 AltStore），连接 iPhone：
   - 导入下载的 `.ipa`，输入 Apple ID（免费账号即可），由 Sideloadly 完成签名与安装；
   - iPhone 需要在 **设置 → 隐私与安全性 → 开发者模式** 中开启开发者模式（iOS 16+），并信任对应描述文件；
5. 免费 Apple ID 签名的 App **7 天过期**，到期重新签名安装即可；AltStore 可自动续签；购买 Apple Developer（$99/年）后可使用 TestFlight 分发。

> 云端 runner 使用 `macos-14`：公开仓库不限时长，私有仓库受每月免费额度限制。
> 如需签名后的调试 IPA，可在仓库 Secrets 中配置 Apple 证书与描述文件，工作流会自动完成签名（不配置时产出未签名 IPA）。

### 方式三：先用网页版代替

网页版 <https://wakedrive.xyz> 已适配手机浏览器。Safari 打开后 **分享 → 添加到主屏幕**，即可像 App 一样全屏运行，包含实时监测、事件、报告、AI 等完整功能，无需构建。摄像头与本地模型推理在 Safari 中直接可用（受 iOS 内存限制，模型首次加载会稍慢）。

## 四个本地模型

| 模型 | 运行方式 | 用途 | 触发事件 |
|---|---|---|---|
| MediaPipe Face Landmarker | MediaPipeTasksVision 0.10.14，CPU，逐帧 | 人脸关键点、EAR / MAR、眨眼与张嘴 blendshape | 闭眼、哈欠 |
| YOLO11n（ONNX） | ONNX Runtime iOS | 手机检测（COCO 类别 67） | 使用手机 |
| YOLOv8n-pose（ONNX） | ONNX Runtime iOS | 通话姿态、喝水 / 抬手（手腕—耳朵、手腕—口鼻） | 使用手机、喝水 |
| 6DRepNet360（ONNX） | ONNX Runtime iOS，224 BGR + ImageNet 归一化 | 头部 pitch / yaw（低头、视线偏离） | 低头、视线偏离 |

推理节流与 Android / 网页端一致：人脸 300 ms、手机 900 ms、姿态 1200 ms、头部姿态 700 ms，串行执行以避免发热与资源竞争。

## 功能清单

- 登录 / 注册，登录态与其它端共用账号体系；
- 工作台：今日事件、待处理告警、重度疲劳计数、最近事件、工程模拟演示入口；
- 实时监测：前置摄像头预览 + 人脸框 + 疲劳指数 + EAR / 闭眼时长 / 哈欠分数 + 事件时间轴；
- 个人校准：采集 10 秒正常状态样本，自动推算个人化阈值；
- 分级警报：重度 / 分心 / 无人脸为高频提示音，中度常规提示，喝水温和语音提醒；
- 人脸丢失：短时间温和提醒，长时间丢失升级为弹窗 + 循环警报；
- 车速联动：GPS 速度超过阈值自动开启监测且行驶中禁止关闭，速度为零持续 3 分钟后自动结束；
- 休息点推荐：重度疲劳时按定位查询附近休息点并跳转地图导航；
- AI 解析与 AI 问答：结合当前风险状态给出建议，服务不可用时本地降级；
- 事件记录：等级 / 类型筛选、详情与抓拍、标记已处理、导出 CSV、清空；
- 监测报告：结束监测自动生成并保存，支持风险曲线与事件明细；
- 统计分析：今日 / 近 7 天 / 近 30 天；
- 模型清单与系统设置：阈值、帧数、冷却时间、告警开关与风格、服务器地址；
- 全站统一的深色控制台视觉风格，与网页端保持一致。

## 设计说明

- **深色控制台主题**：与 Web 端保持统一的深色视觉（深底色 + 青色主色 + 圆角卡片），避免系统浅色模式下出现黑白混排；
- **端侧推理**：四个模型全部打包进 App，不上传原始画面，仅在事件触发时随事件提交小尺寸抓拍图；
- **多线程调度**：模型推理在串行队列执行，结合节流间隔控制 CPU 占用与发热；
- **弱网可用**：网络异常时监测与告警仍可用，事件在恢复后随重试上报。

## 常见问题

**Q：Windows 上能编译 iOS 吗？**
A：不能直接编译。可使用仓库内置的 GitHub Actions 云端构建 IPA（见方式二），或先用网页版代替。

**Q：`pod install` 时依赖版本冲突怎么办？**
A：执行 `pod repo update` 后按可用版本调整 `Podfile`；代码使用的 API 在 `MediaPipeTasksVision` 0.10.x 与 `onnxruntime-objc` 1.2x 内稳定。

**Q：提示找不到 `onnxruntime_objc` 模块？**
A：检查 Pods 生成的 modulemap，模块名通常即为 `onnxruntime_objc`；必要时清理 DerivedData 后重新 `pod install`。

**Q：模型资源没有打进 App Bundle？**
A：检查 XcodeGen 生成的 resources 阶段是否包含 `Resources/models`（`project.yml` 中已显式声明），重新执行 `xcodegen generate`。

**Q：`AVCaptureConnection.videoOrientation` 有弃用警告？**
A：iOS 17+ 已标记弃用，仅警告不影响运行，后续可按新 API 迁移。

**Q：首次打开监测页较慢？**
A：6DRepNet 模型 94 MB，首次加载需要数秒；后续会复用本地缓存。建议 iPhone X 及以上机型。

## 已知限制

- 本工程在 Windows 上编写，尚未在本机完成真机编译验证，首次在 Mac 上构建可能需要按提示做少量适配；
- 检测准确率与实时性能受设备性能与光照环境影响，建议在真实驾驶场景中再做一次完整回归；
- 语音提醒依赖系统中文 TTS，不同 iOS 版本音色可能存在差异。
