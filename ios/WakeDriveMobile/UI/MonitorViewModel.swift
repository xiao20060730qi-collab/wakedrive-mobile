import AVFoundation
import Combine
import CoreLocation
import UIKit

/// Orchestrates camera → four models → risk engine → alarms → uploads,
/// mirroring the web/Android behaviour (face loss, yawn frequency, speed gate, reports).
final class MonitorViewModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    // Published UI state
    @Published var modelReady = false
    @Published var faceAvailable = false
    @Published var analyzing = false
    @Published var score = 0
    @Published var riskText = "等待监测"
    @Published var riskLevel = "none"
    @Published var statusText = "模型加载中…"
    @Published var metricsLine = "EAR -- · 闭眼 0 ms · 哈欠 0% · 本次告警 0"
    @Published var timeline: [String] = []
    @Published var banner: String?
    @Published var faceBox: CGRect?
    @Published var faceLostAlert = false
    @Published var restStops: [[String: Any]] = []
    @Published var restStopAlert = false
    @Published var eventCount = 0
    @Published var speedText = "车速：-- km/h"
    @Published var driving = false
    @Published var calibrating = false

    let camera = CameraController()
    private let alarm = AlarmManager()
    private let pipeline = DispatchQueue(label: "wakedrive.pipeline")
    private let stateLock = NSLock()

    private var faceAnalyzer: FaceAnalyzer?
    private var phoneDetector: PhoneDetector?
    private var poseDetector: PoseDetector?
    private var headEstimator: HeadPoseEstimator?

    private var tracker = TemporalRiskTracker()
    private var coordinator = RiskCoordinator()
    private var recorder: SessionRecorder?
    private var settings: [String: Any] = [:]

    private var latestImage: UIImage?
    private var latestFace: FaceMetrics?
    private var lastFaceRunAt: CFTimeInterval = 0
    private var lastDetAt: CFTimeInterval = 0
    private var lastPoseAt: CFTimeInterval = 0
    private var lastHeadAt: CFTimeInterval = 0
    private var phoneVisible = false
    private var phoneBox: PhoneBox?
    private var callingSince: Date?
    private var lastCallEventAt = Date.distantPast
    private var drinkSince: Date?
    private var lastDrinkEventAt = Date.distantPast
    private var drinkTimes: [Date] = []
    private var downSince: Date?
    private var awaySince: Date?
    private var lastDownAt = Date.distantPast
    private var lastAwayAt = Date.distantPast

    private var lastFaceSeenAt = Date()
    private var lastFaceReminderAt = Date.distantPast
    private var faceLostFired = false
    private var faceLostAlarmActive = false
    private var faceLossTimer: Timer?
    private var lastRestStopAt = Date.distantPast

    private let speedManager = CLLocationManager()
    private var zeroSince: Date?
    private var stopForRestHandler: (() -> Void)?

    override init() {
        super.init()
        camera.onFrame = { [weak self] image in
            self?.pipeline.async { self?.process(image) }
        }
        speedManager.delegate = self
        speedManager.desiredAccuracy = kCLLocationAccuracyBest
    }

    // MARK: setup

    func loadModels() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let face = try? FaceAnalyzer()
            let phone = try? PhoneDetector()
            let pose = try? PoseDetector()
            let head = try? HeadPoseEstimator()
            DispatchQueue.main.async {
                self.faceAnalyzer = face
                self.phoneDetector = phone
                self.poseDetector = pose
                self.headEstimator = head
                self.faceAvailable = face != nil
                self.modelReady = face != nil || phone != nil || pose != nil || head != nil
                self.statusText = face == nil ? "人脸模型在当前设备不可用，其余模型已就绪" : "点击开启摄像头开始监测"
            }
        }
        Task { await loadSettings() }
    }

    private func loadSettings() async {
        guard let remote = await ApiClient.shared.getSettings() else { return }
        settings = remote
        await MainActor.run {
            tracker = TemporalRiskTracker(
                earThreshold: Float(remote["earThreshold"] as? Double ?? 0.25),
                marThreshold: Float(remote["marThreshold"] as? Double ?? 0.55),
                mildFrames: remote["mildFrames"] as? Int ?? 2,
                mediumFrames: remote["mediumFrames"] as? Int ?? 5,
                heavyFrames: remote["heavyFrames"] as? Int ?? 9,
                detectIntervalMs: 300
            )
            alarm.configure(
                enabled: remote["alarmEnabled"] as? Bool ?? true,
                style: remote["alarmStyle"] as? String ?? "bell",
                cooldownMs: remote["cooldownMs"] as? Int ?? 5000
            )
        }
    }

    func start() {
        guard !analyzing else { return }
        analyzing = true
        lastFaceSeenAt = Date()
        faceLostFired = false
        faceLostAlarmActive = false
        tracker.reset()
        coordinator.reset()
        recorder = SessionRecorder(settings: settings)
        recorder?.start()
        camera.configure()
        camera.start()
        speedManager.requestWhenInUseAuthorization()
        speedManager.startUpdatingLocation()
        faceLossTimer?.invalidate()
        faceLossTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.checkFaceLoss()
        }
    }

    func stop() {
        guard analyzing else { return }
        analyzing = false
        camera.stop()
        faceLossTimer?.invalidate()
        faceLossTimer = nil
        speedManager.stopUpdatingLocation()
        driving = false
        alarm.stop()
        tracker.reset()
        coordinator.reset()
        latestFace = nil
        latestImage = nil
        DispatchQueue.main.async {
            self.faceBox = nil
            self.statusText = "点击开启摄像头开始监测"
            self.score = 0
            self.riskText = "等待监测"
            self.riskLevel = "none"
            self.speedText = "车速：-- km/h"
        }
        if let payload = recorder?.finish() {
            Task {
                try? await ApiClient.shared.createReport(payload)
                await MainActor.run {
                    self.banner = "报告已保存到当前账号"
                }
            }
        }
        recorder = nil
    }

    // MARK: pipeline

    private func process(_ image: UIImage) {
        guard analyzing else { return }
        let normalized = ImageUtils.normalized(image)
        let now = CACurrentMediaTime()
        stateLock.lock()
        latestImage = normalized
        stateLock.unlock()

        if now - lastFaceRunAt >= 0.3, let analyzer = faceAnalyzer {
            lastFaceRunAt = now
            let metrics = analyzer.analyze(normalized)
            stateLock.lock()
            latestFace = metrics
            stateLock.unlock()
            let timestamp = Int(now * 1000)
            let result = tracker.update(metrics: metrics, now: timestamp)
            recorder?.add(metrics, result: result)
            handleFaceUI(metrics: metrics, result: result)
            if metrics.faceDetected {
                lastFaceSeenAt = Date()
                if faceLostFired || faceLostAlarmActive {
                    faceLostFired = false
                    faceLostAlarmActive = false
                    alarm.stop()
                    DispatchQueue.main.async {
                        self.faceLostAlert = false
                        self.banner = "已重新检测到人脸"
                    }
                }
            }
            handleRisk(result: result)
        }

        if now - lastDetAt >= 0.9, let detector = phoneDetector {
            lastDetAt = now
            phoneBox = detector.detect(normalized)
            phoneVisible = phoneBox != nil
        }

        if now - lastPoseAt >= 1.2, let detector = poseDetector {
            lastPoseAt = now
            let pose = detector.detect(normalized, phoneVisible: phoneVisible)
            stateLock.lock()
            let face = latestFace
            stateLock.unlock()
            var phoneNearFace = false
            if phoneVisible, let box = phoneBox, let face, face.faceDetected, face.height > 0 {
                let imageSize = normalized.size
                let faceCenterX = face.left + face.width / 2
                let faceCenterY = face.top + face.height / 2
                let distance = hypot(box.centerX / imageSize.width - faceCenterX, box.centerY / imageSize.height - faceCenterY)
                phoneNearFace = distance < face.height * 0.6
            }
            handleCalling((phoneVisible && phoneNearFace) || pose.callingHeld)
            handleDrink(pose.handToMouth)
        }

        if now - lastHeadAt >= 0.7, let estimator = headEstimator {
            lastHeadAt = now
            stateLock.lock()
            let face = latestFace
            stateLock.unlock()
            if let face, face.faceDetected, let pose = estimator.estimate(normalized, face: face) {
                handleHeadPose(pose)
            }
        }
    }

    private func currentFace() -> FaceMetrics? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return latestFace
    }

    private func handleFaceUI(metrics: FaceMetrics, result: RiskResult) {
        DispatchQueue.main.async {
            self.score = result.score
            self.riskText = metrics.faceDetected ? result.risk : "未检测到人脸"
            self.riskLevel = result.severity
            self.statusText = metrics.faceDetected ? "已检测到人脸" : "未检测到人脸"
            self.faceBox = metrics.faceDetected ? CGRect(x: metrics.left, y: metrics.top, width: metrics.width, height: metrics.height) : nil
            let ear = result.ear.map { String(format: "%.2f", $0) } ?? "--"
            self.metricsLine = "EAR \(ear) · 闭眼 \(result.closedDurationMs) ms · 哈欠 \(Int(result.yawnScore * 100))% · 本次告警 \(self.eventCount)"
        }
    }

    private func handleRisk(result: RiskResult) {
        if !faceLostAlarmActive {
            switch result.severity {
            case "medium": alarm.update("medium")
            case "high": alarm.update("high")
            default: alarm.stop()
            }
        }
        let timestamp = Int(CACurrentMediaTime() * 1000)
        if let event = coordinator.handle(result, now: timestamp) {
            DispatchQueue.main.async {
                self.showBanner("\(event.severity)：\(event.message)")
            }
            persistEvent(type: event.type, severity: event.severity, ear: event.ear, mar: event.mar, headPose: "", message: event.message)
        }
    }

    private func handleCalling(_ calling: Bool) {
        guard calling else { callingSince = nil; return }
        if callingSince == nil { callingSince = Date() }
        guard let since = callingSince,
              Date().timeIntervalSince(since) >= 1.8,
              Date().timeIntervalSince(lastCallEventAt) >= 12 else { return }
        lastCallEventAt = Date()
        callingSince = nil
        let message = "检测到手持手机贴近耳侧的通话姿态，请专注驾驶。"
        alarm.event("harsh", message)
        DispatchQueue.main.async { self.showBanner("分心：\(message)") }
        persistEvent(type: "PHONE_USE", severity: "中度疲劳", ear: latestFace?.ear ?? 0, mar: latestFace?.mar ?? 0, headPose: "分心使用手机", message: message)
    }

    private func handleDrink(_ handToMouth: Bool) {
        guard handToMouth else { drinkSince = nil; return }
        if drinkSince == nil { drinkSince = Date() }
        guard let since = drinkSince,
              Date().timeIntervalSince(since) >= 1.5,
              Date().timeIntervalSince(lastDrinkEventAt) >= 25 else { return }
        lastDrinkEventAt = Date()
        drinkSince = nil
        drinkTimes.append(Date())
        drinkTimes.removeAll { Date().timeIntervalSince($0) >= 180 }
        let frequent = drinkTimes.count >= 3
        let severity = frequent ? "中度疲劳" : "轻度疲劳"
        let message = frequent
            ? "3 分钟内多次检测到喝水或吸烟等抬手动作，请专注驾驶。"
            : "检测到喝水或手持物品动作，请把双手放回方向盘。"
        alarm.event(frequent ? "harsh" : "gentle", message)
        DispatchQueue.main.async { self.showBanner(message) }
        persistEvent(type: "DRINK", severity: severity, ear: latestFace?.ear ?? 0, mar: latestFace?.mar ?? 0, headPose: "抬手动作", message: message)
    }

    private func handleHeadPose(_ pose: HeadPose) {
        if pose.pitch <= -25 {
            if downSince == nil { downSince = Date() }
            if let since = downSince,
               Date().timeIntervalSince(since) >= 2.2,
               Date().timeIntervalSince(lastDownAt) >= 12 {
                lastDownAt = Date()
                downSince = nil
                let message = "头部下俯约 \(Int(abs(pose.pitch)))°，持续 2 秒，请抬头正视前方。"
                DispatchQueue.main.async { self.showBanner("轻度疲劳：\(message)") }
                persistEvent(type: "HEAD_DOWN", severity: "轻度疲劳", ear: latestFace?.ear ?? 0, mar: latestFace?.mar ?? 0, headPose: "低头", message: message)
            }
        } else {
            downSince = nil
        }
        if abs(pose.yaw) >= 32 {
            if awaySince == nil { awaySince = Date() }
            if let since = awaySince,
               Date().timeIntervalSince(since) >= 2.2,
               Date().timeIntervalSince(lastAwayAt) >= 12 {
                lastAwayAt = Date()
                awaySince = nil
                let message = "视线偏离前方约 \(Int(abs(pose.yaw)))°，持续 2 秒，请留意路况。"
                DispatchQueue.main.async { self.showBanner("中度疲劳：\(message)") }
                persistEvent(type: "HEAD_AWAY", severity: "中度疲劳", ear: latestFace?.ear ?? 0, mar: latestFace?.mar ?? 0, headPose: "左顾右盼", message: message)
            }
        } else {
            awaySince = nil
        }
    }

    private func checkFaceLoss() {
        guard analyzing else { return }
        if faceAnalyzer == nil { return }
        let lost = Date().timeIntervalSince(lastFaceSeenAt)
        if lost >= 40 {
            if !faceLostFired {
                faceLostFired = true
                faceLostAlarmActive = true
                alarm.update("harsh", "长时间未检测到人脸，请正视前方道路！")
                DispatchQueue.main.async { self.faceLostAlert = true }
                persistEvent(
                    type: "FACE_LOST",
                    severity: "中度疲劳",
                    ear: 0,
                    mar: 0,
                    headPose: "未检测到人脸",
                    message: "已超过 \(Int(lost)) 秒未检测到人脸，请正视前方道路并确认摄像头视线。"
                )
            }
        } else if lost >= 12, Date().timeIntervalSince(lastFaceReminderAt) >= 30 {
            lastFaceReminderAt = Date()
            alarm.event("gentle", "未检测到人脸，请保持正视前方。")
        }
    }

    // MARK: events & reports

    private func persistEvent(type: String, severity: String, ear: Float, mar: Float, headPose: String, message: String) {
        recorder?.addEvent(type: type, severity: severity, reason: message, ear: ear, mar: mar)
        var payload: [String: Any] = [
            "type": type,
            "severity": severity,
            "createdAt": ISO8601DateFormatter().string(from: Date()),
            "earValue": Double(min(2, max(0, ear))),
            "marValue": Double(min(5, max(0, mar))),
            "routeName": "",
            "headPose": headPose,
            "message": message,
        ]
        stateLock.lock()
        let snapshotImage = latestImage
        stateLock.unlock()
        if let snapshot = snapshotImage, let base64 = ImageUtils.jpegBase64(snapshot) {
            payload["snapshot"] = base64
        }
        let label = Self.typeLabels[type] ?? type
        DispatchQueue.main.async {
            self.eventCount += 1
            self.timeline.insert("\(Self.timeFormatter.string(from: Date()))  \(label) · \(severity)", at: 0)
            if self.timeline.count > 4 { self.timeline.removeLast() }
            self.metricsLine = "EAR -- · 闭眼 0 ms · 哈欠 0% · 本次告警 \(self.eventCount)"
        }
        Task { try? await ApiClient.shared.createEvent(payload) }
        if severity == "重度疲劳" { recommendRestStops() }
    }

    private func recommendRestStops() {
        guard Date().timeIntervalSince(lastRestStopAt) >= 600 else { return }
        lastRestStopAt = Date()
        stateLock.lock()
        let image = latestImage
        stateLock.unlock()
        _ = image // location comes from CLLocationManager below
        guard let location = speedManager.location else { return }
        Task {
            let items = await ApiClient.shared.restStops(lat: location.coordinate.latitude, lng: location.coordinate.longitude)
            if items.isEmpty { return }
            await MainActor.run {
                self.restStops = items
                self.restStopAlert = true
            }
        }
    }

    // MARK: calibration

    func collectCalibration() async -> (ear: Float, mar: Float, count: Int) {
        await MainActor.run { self.calibrating = true }
        var samples: [(Float, Float)] = []
        let start = Date()
        while Date().timeIntervalSince(start) < 10 {
            if let face = currentFace(), face.faceDetected, face.blinkScore < 0.35, let ear = face.ear, let mar = face.mar {
                samples.append((ear, mar))
            }
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
        await MainActor.run { self.calibrating = false }
        guard samples.count >= 30 else { return (0, 0, samples.count) }
        let ears = samples.map { $0.0 }.sorted()
        let mars = samples.map { $0.1 }.sorted()
        return (ears[ears.count / 2], mars[mars.count / 2], samples.count)
    }

    func applyCalibration(earMedian: Float, marMedian: Float) async {
        let earThreshold = min(0.4, max(0.08, earMedian * 0.72))
        let marThreshold = min(0.8, max(0.35, marMedian * 3 + 0.15))
        try? await ApiClient.shared.putSettings([
            "earThreshold": Double(earThreshold),
            "marThreshold": Double(marThreshold),
        ])
        await MainActor.run {
            tracker = TemporalRiskTracker(
                earThreshold: earThreshold,
                marThreshold: marThreshold,
                mildFrames: settings["mildFrames"] as? Int ?? 2,
                mediumFrames: settings["mediumFrames"] as? Int ?? 5,
                heavyFrames: settings["heavyFrames"] as? Int ?? 9,
                detectIntervalMs: 300
            )
            settings["earThreshold"] = Double(earThreshold)
            settings["marThreshold"] = Double(marThreshold)
            showBanner("个人阈值已保存并生效")
        }
    }

    // MARK: engineering

    func simulate(_ kind: String) {
        switch kind {
        case "fatigue":
            alarm.event("harsh", "（模拟）危险，检测到重度疲劳，请立即停车休息。")
            showBanner("重度疲劳：模拟事件已触发")
            persistEvent(type: "EYE_CLOSE", severity: "重度疲劳", ear: 0.12, mar: 0.2, headPose: "", message: "（工程模式模拟）持续闭眼 3.2 秒，近 60 秒闭眼占比 55%。")
        case "yawn":
            alarm.update("medium", "（模拟）检测到持续哈欠，请尽快休息。")
            showBanner("中度疲劳：模拟哈欠")
            persistEvent(type: "YAWN", severity: "中度疲劳", ear: 0.3, mar: 0.72, headPose: "", message: "（工程模式模拟）持续张嘴 2.6 秒。")
        case "phone":
            alarm.event("harsh", "请勿在驾驶时使用手机！")
            showBanner("分心：模拟手机通话")
            persistEvent(type: "PHONE_USE", severity: "中度疲劳", ear: 0.25, mar: 0.3, headPose: "分心使用手机", message: "（工程模式模拟）检测到手持手机贴近耳侧的通话姿态。")
        case "drink":
            alarm.event("gentle", "请注意，请把双手放回方向盘。")
            showBanner("喝水：模拟抬手动作")
            persistEvent(type: "DRINK", severity: "轻度疲劳", ear: 0.3, mar: 0.2, headPose: "抬手动作", message: "（工程模式模拟）检测到喝水动作。")
        case "lost":
            faceLostFired = true
            faceLostAlarmActive = true
            alarm.update("harsh", "长时间未检测到人脸，请正视前方道路！")
            showBanner("长时间未检测到人脸（模拟）")
            faceLostAlert = true
            persistEvent(type: "FACE_LOST", severity: "中度疲劳", ear: 0, mar: 0, headPose: "未检测到人脸", message: "（工程模式模拟）长时间未检测到人脸。")
        case "reset":
            alarm.stop()
            showBanner("告警已重置")
        default:
            break
        }
    }

    private func showBanner(_ text: String) {
        DispatchQueue.main.async {
            self.banner = text
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                if self.banner == text { self.banner = nil }
            }
        }
    }

    // MARK: speed gate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let speed = locations.last?.speed, speed >= 0 else { return }
        let kmh = speed * 3.6
        DispatchQueue.main.async { self.speedText = String(format: "车速：%.0f km/h", kmh) }
        if speed > 0.5 {
            driving = true
            zeroSince = nil
            if !analyzing, faceAnalyzer != nil {
                DispatchQueue.main.async {
                    self.start()
                    self.showBanner("检测到车辆行驶，监测已自动开启")
                }
            }
        } else {
            driving = false
            if analyzing {
                if zeroSince == nil { zeroSince = Date() }
                if let zero = zeroSince, Date().timeIntervalSince(zero) >= 180 {
                    zeroSince = nil
                    DispatchQueue.main.async {
                        self.stop()
                        self.showBanner("车速持续为零 3 分钟，已确认下车休息，监测自动结束")
                    }
                }
            } else {
                zeroSince = nil
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    // MARK: helpers

    private static let typeLabels = [
        "EYE_CLOSE": "闭上眼", "YAWN": "打哈欠", "PHONE_USE": "使用手机",
        "HEAD_DOWN": "低头", "HEAD_AWAY": "视线偏离", "FACE_LOST": "未检测到人脸", "DRINK": "喝水/抬手",
    ]
    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()
}
