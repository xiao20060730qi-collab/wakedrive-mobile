import AVFoundation
import SwiftUI

struct MonitorScreen: View {
    let initialSim: String?
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel = MonitorViewModel()

    @State private var showAI = false
    @State private var showEngineer = false
    @State private var calibrationAlert = false
    @State private var calibrationMessage = ""
    @State private var calibrationValues: (Float, Float)?
    @State private var cameraDenied = false
    @State private var pendingSim: String?

    var body: some View {
        ZStack(alignment: .top) {
            CameraPreview(session: viewModel.camera.session)
                .ignoresSafeArea()

            faceOverlay

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(viewModel.statusText)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Theme.cyan)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background(Color.black.opacity(0.55))
                        .clipShape(Capsule())
                    Spacer()
                    Button { viewModel.stop(); dismiss() } label: {
                        Text("返回")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .background(Color.black.opacity(0.55))
                            .clipShape(Capsule())
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)

                Spacer()

                if let banner = viewModel.banner {
                    Text(banner)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Color(red: 1, green: 217 / 255, blue: 212 / 255))
                        .frame(maxWidth: .infinity)
                        .padding(12)
                        .background(Color(red: 122 / 255, green: 31 / 255, blue: 26 / 255).opacity(0.92))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 12)
                }

                bottomPanel
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .onAppear {
            viewModel.loadModels()
            pendingSim = initialSim
            if initialSim != nil {
                requestCameraAndStart()
            }
        }
        .sheet(isPresented: $showAI) { NavigationView { AIView().navigationTitle("AI 驾驶解析") } }
        .confirmationDialog("⚙ 工程模式（模拟演示）", isPresented: $showEngineer, titleVisibility: .visible) {
            Button("模拟重度疲劳") { viewModel.simulate("fatigue") }
            Button("模拟持续哈欠") { viewModel.simulate("yawn") }
            Button("模拟手机分心") { viewModel.simulate("phone") }
            Button("模拟喝水动作") { viewModel.simulate("drink") }
            Button("触发长时间无人脸弹窗") { viewModel.simulate("lost") }
            Button("重置告警") { viewModel.simulate("reset") }
            Button("关闭", role: .cancel) {}
        }
        .confirmationDialog("检测到重度疲劳，附近休息点", isPresented: $viewModel.restStopAlert, titleVisibility: .visible) {
            ForEach(Array(viewModel.restStops.prefix(3).enumerated()), id: \.offset) { _, stop in
                Button("\(stop["name"] as? String ?? "服务区") · \(distanceLabel(stop))") {
                    openRestStop(stop)
                }
            }
            Button("关闭", role: .cancel) {}
        }
        .alert("长时间未检测到人脸", isPresented: $viewModel.faceLostAlert) {
            Button("我知道了，保持正视前方") { viewModel.faceLostAlert = false }
        } message: {
            Text("请正视前方道路。低头或遮挡都会中断检测，请保持坐姿并正视摄像头。")
        }
        .alert("校准完成", isPresented: $calibrationAlert) {
            Button("应用校准") {
                if let values = calibrationValues {
                    Task { await viewModel.applyCalibration(earMedian: values.0, marMedian: values.1) }
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text(calibrationMessage)
        }
        .alert("需要摄像头权限", isPresented: $cameraDenied) {
            Button("去设置") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
            Button("取消", role: .cancel) {}
        } message: {
            Text("请在系统设置中允许 WakeDrive 使用摄像头。")
        }
    }

    private var faceOverlay: some View {
        GeometryReader { geometry in
            if let box = viewModel.faceBox {
                // Preview fills the screen and is mirrored for the front camera.
                let scale = max(geometry.size.width / 9, geometry.size.height / 16)
                let renderedWidth = scale * 9
                let renderedHeight = scale * 16
                let offsetX = (geometry.size.width - renderedWidth) / 2
                let offsetY = (geometry.size.height - renderedHeight) / 2
                let x = (1 - box.origin.x - box.width) * renderedWidth + offsetX
                let y = box.origin.y * renderedHeight + offsetY
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Theme.cyan, lineWidth: 3)
                    .frame(width: box.width * renderedWidth, height: box.height * renderedHeight)
                    .position(x: x + box.width * renderedWidth / 2, y: y + box.height * renderedHeight / 2)
            }
        }
        .allowsHitTesting(false)
        .ignoresSafeArea()
    }

    private var bottomPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("疲劳指数 \(viewModel.score)")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Theme.text)
                Spacer()
                Text(viewModel.riskText)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(riskColor)
            }
            Text(viewModel.metricsLine).font(.system(size: 12)).foregroundStyle(Theme.muted)
            if viewModel.timeline.isEmpty {
                Text("本次事件时间轴：暂无告警").font(.system(size: 11)).foregroundStyle(Theme.muted)
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Text("本次事件时间轴：").font(.system(size: 11)).foregroundStyle(Theme.muted)
                    ForEach(viewModel.timeline, id: \.self) { line in
                        Text(line).font(.system(size: 11)).foregroundStyle(Theme.muted)
                    }
                }
            }

            HStack(spacing: 8) {
                actionButton("个人校准") { Task { await calibrate() } }
                actionButton("✦ AI 解析") { showAI = true }
                actionButton("⚙ 工程模式") { showEngineer = true }
            }

            HStack(spacing: 8) {
                Button {
                    if viewModel.analyzing {
                        viewModel.stop()
                    } else if viewModel.driving {
                        viewModel.banner = "车辆行驶中监测不能关闭"
                    } else {
                        requestCameraAndStart()
                    }
                } label: {
                    Text(viewModel.analyzing ? "■ 停止监测" : "▶ 开启监测")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                        .frame(maxWidth: .infinity).frame(height: 50)
                        .background(Theme.cyan).clipShape(Capsule())
                }
                .frame(width: 170)
                Button { dismiss() } label: {
                    Text("事件记录").font(.system(size: 13)).foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity).frame(height: 50)
                        .background(Theme.card).clipShape(Capsule())
                        .overlay(Capsule().stroke(Theme.cardStroke))
                }
            }
            Text(viewModel.speedText).font(.system(size: 11)).foregroundStyle(Theme.muted)
        }
        .padding(16)
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.cardStroke))
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
    }

    private var riskColor: Color {
        switch viewModel.riskLevel {
        case "high": return Theme.danger
        case "medium", "mild": return Theme.warning
        case "normal": return Theme.safe
        default: return Theme.muted
        }
    }

    private func actionButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12))
                .foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity).frame(height: 42)
                .background(Theme.background).clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardStroke))
        }
    }

    private func requestCameraAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            viewModel.start()
            firePendingSim()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted {
                        viewModel.start()
                        firePendingSim()
                    } else {
                        cameraDenied = true
                    }
                }
            }
        default:
            cameraDenied = true
        }
    }

    private func firePendingSim() {
        guard let sim = pendingSim else { return }
        pendingSim = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            viewModel.simulate(sim)
        }
    }

    private func calibrate() async {
        let result = await viewModel.collectCalibration()
        if result.count < 30 {
            calibrationMessage = "有效样本不足（\(result.count)/30），请正视摄像头保持自然睁眼后重试。"
            calibrationAlert = true
            return
        }
        let earThreshold = min(0.4, max(0.08, result.ear * 0.72))
        let marThreshold = min(0.8, max(0.35, result.mar * 3 + 0.15))
        calibrationValues = (result.ear, result.mar)
        calibrationMessage = "有效样本 \(result.count) 个。建议 EAR \(String(format: "%.3f", earThreshold))，MAR \(String(format: "%.3f", marThreshold))。应用后保存到当前账号并立即生效。"
        calibrationAlert = true
    }

    private func distanceLabel(_ stop: [String: Any]) -> String {
        let meters = stop["distanceMeters"] as? Int ?? 0
        return meters >= 1000 ? String(format: "%.1f 公里", Double(meters) / 1000) : "\(meters) 米"
    }

    private func openRestStop(_ stop: [String: Any]) {
        guard let lat = stop["lat"] as? Double, let lng = stop["lng"] as? Double,
              let name = stop["name"] as? String,
              let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "https://uri.amap.com/navigation?to=\(lng),\(lat),\(encoded)&mode=car&coordinate=gaode&callnative=1") else { return }
        UIApplication.shared.open(url)
    }
}
