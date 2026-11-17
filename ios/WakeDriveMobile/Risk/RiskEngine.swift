import Foundation

struct RiskResult {
    var faceDetected = false
    var score = 0
    var severity = "none"
    var risk = "未知"
    var reasons: [String] = []
    var triggerType: String?
    var closedDurationMs: Int = 0
    var yawnDurationMs: Int = 0
    var closedRatio: Float?
    var windowValidMs = 0
    var ear: Float?
    var mar: Float?
    var eyeClosed = false
    var mouthOpen = false
    var blinkScore: Float = 0
    var yawnScore: Float = 0
}

struct RiskEvent {
    let type: String
    let severity: String
    let ear: Float
    let mar: Float
    let message: String
}

/// Sliding-window eye / yawn tracker, ported from the web client.
final class TemporalRiskTracker {
    private struct Segment { let start: Int; let end: Int; let valid: Bool; let closed: Bool }

    private let earThreshold: Float
    private let marThreshold: Float
    private let mildFrames: Int
    private let mediumFrames: Int
    private let heavyFrames: Int
    private let detectIntervalMs: Int

    private var previousTime: Int?
    private var previousFace = false
    private var previousClosed = false
    private var eyeStart: Int?
    private var mouthStart: Int?
    private var segments: [Segment] = []

    init(earThreshold: Float = 0.25, marThreshold: Float = 0.55,
         mildFrames: Int = 2, mediumFrames: Int = 5, heavyFrames: Int = 9,
         detectIntervalMs: Int = 300) {
        self.earThreshold = earThreshold
        self.marThreshold = marThreshold
        self.mildFrames = mildFrames
        self.mediumFrames = mediumFrames
        self.heavyFrames = heavyFrames
        self.detectIntervalMs = detectIntervalMs
    }

    func reset() {
        previousTime = nil
        previousFace = false
        previousClosed = false
        eyeStart = nil
        mouthStart = nil
        segments.removeAll()
    }

    func update(metrics: FaceMetrics, now: Int) -> RiskResult {
        if let previous = previousTime, now < previous { reset() }
        let blinkEvidence = metrics.blinkScore >= 0.35
        let eyeClosed: Bool
        if let ear = metrics.ear {
            eyeClosed = metrics.faceDetected && ear < earThreshold && blinkEvidence
        } else {
            eyeClosed = metrics.faceDetected && metrics.blinkScore >= 0.58
        }
        let mouthOpen: Bool
        if let mar = metrics.mar {
            mouthOpen = metrics.faceDetected && mar > marThreshold
        } else {
            mouthOpen = metrics.faceDetected && metrics.yawnScore >= 0.65
        }

        let delta = previousTime.map { now - $0 } ?? 0
        let continuous = previousTime != nil && (0...1500).contains(delta) && previousFace && metrics.faceDetected
        if let previous = previousTime, delta > 0 {
            segments.append(Segment(start: previous, end: now, valid: continuous, closed: continuous && previousClosed))
        }
        if !continuous { eyeStart = nil; mouthStart = nil }
        if eyeClosed { if eyeStart == nil { eyeStart = now } } else { eyeStart = nil }
        if mouthOpen { if mouthStart == nil { mouthStart = now } } else { mouthStart = nil }

        let cutoff = now - 60_000
        segments.removeAll { $0.end <= cutoff }
        var validMs = 0
        var closedMs = 0
        for segment in segments {
            let span = segment.end - max(cutoff, segment.start)
            if segment.valid { validMs += span }
            if segment.closed { closedMs += span }
        }
        let closedDuration = eyeStart.map { now - $0 } ?? 0
        let yawnDuration = mouthStart.map { now - $0 } ?? 0
        let closedRatio: Float? = validMs >= 10_000 ? Float(closedMs) / Float(validMs) : nil

        let mild = max(300, mildFrames * detectIntervalMs)
        let medium = max(700, max(mild + 100, mediumFrames * detectIntervalMs))
        let heavy = max(1200, max(medium + 100, heavyFrames * detectIntervalMs))
        let eyeLimits = [mild, medium, heavy]
        let mouthLimits = [max(1500, mild), max(2500, medium), max(4000, heavy)]

        var eyeLevel = 0
        var mouthLevel = 0
        for index in 0..<3 {
            if closedDuration >= eyeLimits[index] { eyeLevel = index + 1 }
            if yawnDuration >= mouthLimits[index] { mouthLevel = index + 1 }
        }
        var reasons: [String] = []
        if closedDuration >= eyeLimits[0] {
            reasons.append(String(format: "持续闭眼 %.1f 秒", Double(closedDuration) / 1000))
        }
        if yawnDuration >= mouthLimits[0] {
            reasons.append(String(format: "持续张嘴 %.1f 秒", Double(yawnDuration) / 1000))
        }
        if validMs >= 10_000, let ratio = closedRatio {
            let ratioLimits: [Float] = [0.15, 0.25, 0.4]
            for index in 0..<3 where ratio >= ratioLimits[index] {
                eyeLevel = max(eyeLevel, index + 1)
            }
            if ratio >= 0.15 {
                reasons.append("近 60 秒有效检测中闭眼占比 \(Int(ratio * 100))%")
            }
        }
        let level = max(eyeLevel, mouthLevel)
        let scores = [0, 30, 60, 90]
        let severities = ["normal", "mild", "medium", "high"]
        let risks = ["正常", "轻度关注", "中度风险", "高风险"]
        let trigger: String? = level == 0 ? nil : (mouthLevel > eyeLevel ? "YAWN" : "EYE_CLOSE")

        previousTime = now
        previousFace = metrics.faceDetected
        previousClosed = eyeClosed

        return RiskResult(
            faceDetected: metrics.faceDetected,
            score: scores[level],
            severity: severities[level],
            risk: risks[level],
            reasons: reasons,
            triggerType: trigger,
            closedDurationMs: closedDuration,
            yawnDurationMs: yawnDuration,
            closedRatio: closedRatio,
            windowValidMs: validMs,
            ear: metrics.ear,
            mar: metrics.mar,
            eyeClosed: eyeClosed,
            mouthOpen: mouthOpen,
            blinkScore: metrics.blinkScore,
            yawnScore: metrics.yawnScore
        )
    }
}

/// Event de-duplication + 3-minute yawn-frequency escalation.
final class RiskCoordinator {
    private let cooldownMs: Int
    private var lastSeverity = "none"
    private var lastEventAt = 0
    private var yawnTimes: [Int] = []

    init(cooldownMs: Int = 5000) { self.cooldownMs = cooldownMs }

    func reset() {
        lastSeverity = "none"
        lastEventAt = 0
        yawnTimes.removeAll()
    }

    func handle(_ result: RiskResult, now: Int) -> RiskEvent? {
        guard result.faceDetected, ["mild", "medium", "high"].contains(result.severity) else {
            reset()
            return nil
        }
        if result.severity == lastSeverity && now - lastEventAt < cooldownMs { return nil }
        lastSeverity = result.severity
        lastEventAt = now

        let eyeEvidence = result.reasons.contains { $0.contains("持续闭眼") || $0.contains("闭眼占比") }
        let mouthEvidence = result.reasons.contains { $0.contains("持续张嘴") }
        let type: String
        if let trigger = result.triggerType, ["EYE_CLOSE", "YAWN"].contains(trigger) {
            type = trigger
        } else if mouthEvidence && !eyeEvidence {
            type = "YAWN"
        } else if eyeEvidence {
            type = "EYE_CLOSE"
        } else if result.yawnScore > result.blinkScore && result.closedDurationMs == 0 {
            type = "YAWN"
        } else {
            type = "EYE_CLOSE"
        }

        var severity: String
        switch result.severity {
        case "high": severity = "重度疲劳"
        case "medium": severity = "中度疲劳"
        default: severity = "轻度疲劳"
        }
        var message = result.reasons.joined(separator: "；")
        if message.isEmpty {
            message = result.severity == "high" ? "检测到重度疲劳，请在安全地点停车休息。" : "检测到疲劳风险，请关注状态并安排休息。"
        }
        if type == "YAWN" {
            yawnTimes.append(now)
            yawnTimes.removeAll { now - $0 >= 180_000 }
            if yawnTimes.count >= 2 {
                if severity != "重度疲劳" { severity = "中度疲劳" }
                message = "3 分钟内已打哈欠 \(yawnTimes.count) 次，\(message)"
            }
        }
        return RiskEvent(type: type, severity: severity, ear: result.ear ?? 0, mar: result.mar ?? 0, message: message)
    }
}

/// One-second bucketed session recorder matching the Python /reports contract.
final class SessionRecorder {
    private struct Sample {
        var offsetMs: Int
        var spanMs: Int
        var validMs: Int
        var closedMs: Int
        var score: Int?
        var severity: String
        var reasons: [String]
    }

    private var startedAt: Date?
    private var lastOffset = 0
    private var ended = false
    private var previous: (valid: Bool, eyeClosed: Bool, score: Int?, severity: String, reasons: [String])?
    private var samples: [Sample] = []
    private var events: [[String: Any]] = []
    private let settings: [String: Any]
    let clientId = UUID().uuidString
    private(set) var eventCount = 0

    init(settings: [String: Any] = [:]) { self.settings = settings }

    func start() { if startedAt == nil { startedAt = Date() } }

    private func offset(_ now: Date) -> Int {
        guard let startedAt else { return 0 }
        return min(86_400_000, max(0, Int(now.timeIntervalSince(startedAt) * 1000)))
    }

    func add(_ metrics: FaceMetrics, result: RiskResult, now: Date = Date()) {
        start()
        if ended { return }
        let currentOffset = offset(now)
        if currentOffset < lastOffset { return }
        let valid = metrics.faceDetected
        let known = (
            valid: valid,
            eyeClosed: valid && result.eyeClosed,
            score: valid ? min(100, max(0, result.score)) : nil,
            severity: valid ? (["normal", "mild", "medium", "high"].contains(result.severity) ? result.severity : "normal") : "none",
            reasons: valid ? Array(result.reasons.prefix(5)) : []
        )
        let continuous = previous?.valid == true && known.valid && currentOffset - lastOffset <= 1500
        let interval: (valid: Bool, eyeClosed: Bool, score: Int?, severity: String, reasons: [String])
        if continuous, let prior = previous {
            interval = (
                valid: true,
                eyeClosed: prior.eyeClosed,
                score: max(prior.score ?? 0, known.score ?? 0),
                severity: worse(prior.severity, known.severity),
                reasons: Array(Set(prior.reasons + known.reasons)).prefix(5).map { $0 }
            )
        } else {
            interval = (false, false, nil, "none", [])
        }
        appendInterval(from: lastOffset, to: currentOffset, interval: interval)
        lastOffset = currentOffset
        previous = known
    }

    func addEvent(type: String, severity: String, reason: String, ear: Float?, mar: Float?, now: Date = Date()) {
        start()
        if ended { return }
        let level = severity == "重度疲劳" ? "high" : severity == "中度疲劳" ? "medium" : "mild"
        events.append([
            "offsetMs": offset(now),
            "type": type,
            "severity": level,
            "reason": String(reason.prefix(200)),
            "ear": ear.map { min(2, max(0, Double($0))) } ?? NSNull(),
            "mar": mar.map { min(5, max(0, Double($0))) } ?? NSNull(),
        ])
        eventCount = events.count
    }

    func finish(now: Date = Date()) -> [String: Any] {
        start()
        ended = true
        let stop = max(lastOffset, offset(now))
        if stop > lastOffset {
            let tail = previous ?? (false, false, nil, "none", [])
            let validTail = tail.valid && stop - lastOffset <= 1500
            appendInterval(
                from: lastOffset,
                to: stop,
                interval: (validTail, validTail && tail.eyeClosed, validTail ? tail.score : nil, validTail ? tail.severity : "none", validTail ? tail.reasons : [])
            )
            lastOffset = stop
        }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let start = startedAt ?? Date()
        let sampleDicts: [[String: Any]] = samples.map { sample in
            [
                "offsetMs": sample.offsetMs,
                "spanMs": sample.spanMs,
                "validMs": sample.validMs,
                "closedMs": sample.closedMs,
                "score": sample.score ?? NSNull(),
                "severity": sample.severity,
                "reasons": sample.reasons,
            ]
        }
        var payload: [String: Any] = [
            "clientId": clientId,
            "source": "camera",
            "title": "iOS 监测",
            "startedAt": formatter.string(from: start),
            "endedAt": formatter.string(from: start.addingTimeInterval(Double(stop) / 1000)),
            "samples": sampleDicts,
            "events": events,
        ]
        payload["settings"] = settings.filter { ["earThreshold", "marThreshold", "mildFrames", "mediumFrames", "heavyFrames", "detectIntervalMs"].contains($0.key) }
        return payload
    }

    private func appendInterval(from: Int, to: Int, interval: (valid: Bool, eyeClosed: Bool, score: Int?, severity: String, reasons: [String])) {
        var cursor = from
        while cursor < to {
            let bucketEnd = min(to, (cursor / 1000 + 1) * 1000)
            let span = bucketEnd - cursor
            let sample = Sample(
                offsetMs: cursor,
                spanMs: span,
                validMs: interval.valid ? span : 0,
                closedMs: interval.valid && interval.eyeClosed ? span : 0,
                score: interval.score,
                severity: interval.severity,
                reasons: interval.reasons
            )
            if let last = samples.last, last.offsetMs + last.spanMs == cursor, last.offsetMs / 1000 == cursor / 1000 {
                samples[samples.count - 1] = merge(last, sample)
            } else {
                samples.append(sample)
            }
            cursor = bucketEnd
        }
    }

    private func merge(_ a: Sample, _ b: Sample) -> Sample {
        let worst = (b.score ?? -1) > (a.score ?? -1) ? b : a
        return Sample(
            offsetMs: a.offsetMs,
            spanMs: a.spanMs + b.spanMs,
            validMs: a.validMs + b.validMs,
            closedMs: a.closedMs + b.closedMs,
            score: worst.score,
            severity: worst.severity,
            reasons: Array(Set(worst.reasons + a.reasons + b.reasons)).prefix(5).map { $0 }
        )
    }

    private func worse(_ a: String, _ b: String) -> String {
        let order = ["none", "normal", "mild", "medium", "high"]
        return (order.firstIndex(of: a) ?? 0) >= (order.firstIndex(of: b) ?? 0) ? a : b
    }
}
