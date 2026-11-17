import SwiftUI

struct ShellView: View {
    let onLogout: () -> Void
    @State private var tab = "dashboard"
    @State private var showMonitor = false
    @State private var pendingSim: String?

    private let tabs: [(key: String, label: String, icon: String)] = [
        ("dashboard", "工作台", "house"),
        ("monitor", "监测", "eye"),
        ("models", "模型", "square.stack.3d.up"),
        ("events", "事件", "list.bullet.rectangle"),
        ("reports", "报告", "chart.bar.doc.horizontal"),
        ("stats", "统计", "chart.bar"),
        ("ai", "AI", "sparkles"),
        ("settings", "设置", "gearshape"),
    ]

    var body: some View {
        VStack(spacing: 0) {
            Group {
                switch tab {
                case "models": ModelsView()
                case "events": EventsView()
                case "reports": ReportsView()
                case "stats": StatsView()
                case "ai": AIView()
                case "settings": SettingsView(onLogout: onLogout)
                default: DashboardView(onStartMonitor: { sim in
                    pendingSim = sim
                    showMonitor = true
                })
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            bottomBar
        }
        .background(Theme.background.ignoresSafeArea())
        .fullScreenCover(isPresented: $showMonitor) {
            MonitorScreen(initialSim: pendingSim)
        }
    }

    private var bottomBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(tabs, id: \.key) { item in
                    let active = tab == item.key
                    Button {
                        if item.key == "monitor" {
                            pendingSim = nil
                            showMonitor = true
                        } else {
                            tab = item.key
                        }
                    } label: {
                        VStack(spacing: 2) {
                            Image(systemName: item.icon).font(.system(size: 17))
                            Text(item.label).font(.system(size: 10, weight: active ? .bold : .regular))
                        }
                        .foregroundStyle(active ? Theme.cyan : Theme.muted)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(
                            Group {
                                if active {
                                    Capsule().fill(Color(red: 20 / 255, green: 56 / 255, blue: 74 / 255))
                                        .overlay(Capsule().stroke(Color(red: 47 / 255, green: 110 / 255, blue: 134 / 255), lineWidth: 1))
                                } else {
                                    Color.clear
                                }
                            }
                        )
                    }
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 6)
        }
        .background(Color(red: 8 / 255, green: 19 / 255, blue: 31 / 255))
    }
}

struct DashboardView: View {
    let onStartMonitor: (String?) -> Void
    @State private var summary: [String: Any] = [:]
    @State private var events: [[String: Any]] = []
    @State private var me: [String: Any]?
    @State private var showAdmin = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("驾驶工作台").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 16)
                Text("实时掌握驾驶员、车辆和疲劳风险，让每一次提醒都有依据。")
                    .font(.system(size: 12)).foregroundStyle(Theme.muted)

                VStack(alignment: .leading, spacing: 10) {
                    Text("当前账号数据").font(.system(size: 11)).foregroundStyle(Theme.muted)
                    HStack {
                        metric("今日疲劳事件", value: "\(summary["totalRecords"] as? Int ?? 0)")
                        metric("待处理告警", value: "\(pendingCount)")
                        metric("今日重度疲劳", value: "\(heavyCount)")
                    }
                }
                .wdCard()

                VStack(spacing: 10) {
                    Button { onStartMonitor(nil) } label: {
                        Text("▶ 开始实时监测")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(LinearGradient(colors: [Theme.cyan, Color(red: 47 / 255, green: 184 / 255, blue: 216 / 255)],
                                                       startPoint: .leading, endPoint: .trailing))
                            .clipShape(Capsule())
                    }
                }
                .wdCard()

                VStack(alignment: .leading, spacing: 8) {
                    Text("⚙ 工程模拟演示").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                    Text("无需等待真实疲劳即可演示完整告警链路；事件会保存到当前账号并带「工程模式模拟」标记。")
                        .font(.system(size: 11)).foregroundStyle(Theme.muted)
                    HStack {
                        simButton("模拟开启监测", "start")
                        simButton("重度疲劳演示", "fatigue")
                    }
                    HStack {
                        simButton("手机分心演示", "phone")
                        simButton("无人脸弹窗演示", "lost")
                    }
                }
                .wdCard()

                VStack(alignment: .leading, spacing: 6) {
                    Text("最近事件").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                    if events.isEmpty {
                        Text("暂无事件记录").font(.system(size: 12)).foregroundStyle(Theme.muted)
                    } else {
                        ForEach(Array(events.prefix(5).enumerated()), id: \.offset) { _, event in
                            Text("\(Self.typeLabels[event["type"] as? String ?? ""] ?? "事件") · \(event["severity"] as? String ?? "") · \((event["createdAt"] as? String ?? "").prefix(16).replacingOccurrences(of: "T", with: " "))")
                                .font(.system(size: 12)).foregroundStyle(Theme.muted)
                        }
                    }
                }
                .wdCard()

                if me?["role"] as? String == "admin" {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("管理员后台").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                        Button { showAdmin = true } label: {
                            Text("进入管理员后台 →")
                                .font(.system(size: 14))
                                .foregroundStyle(Theme.text)
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)
                                .background(Theme.card)
                                .clipShape(Capsule())
                                .overlay(Capsule().stroke(Color(red: 47 / 255, green: 110 / 255, blue: 134 / 255)))
                        }
                    }
                    .wdCard()
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .background(Theme.background.ignoresSafeArea())
        .sheet(isPresented: $showAdmin) { AdminConsoleView() }
        .task { await load() }
    }

    private var pendingCount: Int {
        (summary["typeDistribution"] as? [String: Any]).map { ($0["PHONE_USE"] as? Int ?? 0) + ($0["FACE_LOST"] as? Int ?? 0) } ?? 0
    }

    private var heavyCount: Int {
        (summary["severityDistribution"] as? [String: Any])?["重度疲劳"] as? Int ?? 0
    }

    private func metric(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 11)).foregroundStyle(Theme.muted)
            Text(value).font(.system(size: 24, weight: .bold)).foregroundStyle(Theme.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func simButton(_ label: String, _ sim: String) -> some View {
        Button { onStartMonitor(sim) } label: {
            Text(label)
                .font(.system(size: 13))
                .foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity).frame(height: 44)
                .background(Theme.card)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 47 / 255, green: 110 / 255, blue: 134 / 255)))
        }
    }

    private func load() async {
        me = await ApiClient.shared.me()
        summary = await ApiClient.shared.summary(range: "today") ?? [:]
        events = (try? await ApiClient.shared.fetchEvents(limit: 5)) ?? []
    }

    static let typeLabels = [
        "EYE_CLOSE": "闭眼", "YAWN": "打哈欠", "PHONE_USE": "使用手机", "HEAD_DOWN": "低头",
        "HEAD_AWAY": "视线偏离", "FACE_LOST": "未检测到人脸", "DRINK": "喝水/抬手",
    ]
}

struct ModelsView: View {
    private let ready: [(String, String, String)] = [
        ("MediaPipe Face Landmarker", "人脸关键点 · 眨眼/张嘴 blendshape", "本地任务模型 3.8MB"),
        ("YOLO11n", "手机检测（COCO 类 67）", "ONNX 10.9MB · ONNX Runtime"),
        ("YOLOv8n-pose", "通话 / 喝水抬手姿态", "ONNX 13.5MB · ONNX Runtime"),
        ("6DRepNet360", "头部姿态：低头 / 视线偏离", "ONNX 94MB · ONNX Runtime"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("模型清单").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 16)
                Text("全部模型在手机本地推理，画面不上传服务器；事件与抓拍仅在触发时保存到当前账号。")
                    .font(.system(size: 12)).foregroundStyle(Theme.muted)
                Text("已接入（4）").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.cyan)
                ForEach(ready, id: \.0) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.0).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                        Text(item.1).font(.system(size: 12)).foregroundStyle(Theme.muted)
                        Text("\(item.2) · 已接入").font(.system(size: 11)).foregroundStyle(Theme.safe)
                    }
                    .wdCard()
                }
                Text("待接入（2）").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.muted)
                ForEach([
                    ("MobileNetV3-Small", "缺少驾驶任务标注数据，暂不冒险接入"),
                    ("因果 TCN", "缺少按帧标注的疲劳时序数据，公开权重无法直接用于本任务"),
                ], id: \.0) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.0).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.muted)
                        Text(item.1).font(.system(size: 11)).foregroundStyle(Theme.muted)
                    }
                    .wdCard()
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .background(Theme.background.ignoresSafeArea())
    }
}
