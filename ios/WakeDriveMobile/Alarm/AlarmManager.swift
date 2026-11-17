import AVFoundation
import AudioToolbox

/// Graduated alerts: piercing double-beeps for severe/distraction/face-lost,
/// medium loop at cooldown, gentle acknowledgement for drinking.
final class AlarmManager {
    private let synthesizer = AVSpeechSynthesizer()
    private var timer: Timer?
    private var level = "none"
    private var enabled = true
    private var silentStyle = false
    private var mediumCooldown: TimeInterval = 5

    private func ensureMain(_ action: @escaping () -> Void) {
        if Thread.isMainThread { action() } else { DispatchQueue.main.async(execute: action) }
    }

    func configure(enabled: Bool, style: String, cooldownMs: Int) {
        guard Thread.isMainThread else {
            ensureMain { self.configure(enabled: enabled, style: style, cooldownMs: cooldownMs) }
            return
        }
        self.enabled = enabled
        silentStyle = style == "silent"
        mediumCooldown = min(60, max(0.1, Double(cooldownMs) / 1000))
        if !enabled || silentStyle { stop() }
    }

    func update(_ next: String, _ message: String? = nil) {
        guard Thread.isMainThread else {
            ensureMain { self.update(next, message) }
            return
        }
        guard enabled, !silentStyle else { return }
        let normalized = ["high", "harsh", "medium"].contains(next) ? next : "none"
        if normalized == "none" { stop(); return }
        if normalized == level, timer != nil { return }
        stop()
        level = normalized
        playTone(normalized)
        speak(message ?? defaultVoice(normalized), fast: normalized != "medium")
        let interval: TimeInterval = normalized == "high" ? 0.9 : normalized == "harsh" ? 0.7 : mediumCooldown
        timer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            guard let self, self.enabled, !self.silentStyle else { return }
            self.playTone(self.level)
            if self.level == "high" || self.level == "harsh" {
                self.speak(self.defaultVoice(self.level), fast: true)
            }
        }
    }

    func event(_ kind: String, _ message: String? = nil) {
        guard Thread.isMainThread else {
            ensureMain { self.event(kind, message) }
            return
        }
        guard enabled, !silentStyle else { return }
        let normalized = ["high", "harsh", "medium", "gentle"].contains(kind) ? kind : "medium"
        playTone(normalized)
        if let message { speak(message, fast: normalized != "gentle") }
    }

    func stop() {
        guard Thread.isMainThread else {
            ensureMain { self.stop() }
            return
        }
        timer?.invalidate()
        timer = nil
        level = "none"
        synthesizer.stopSpeaking(at: .immediate)
    }

    private func defaultVoice(_ level: String) -> String {
        switch level {
        case "high": return "危险，检测到重度疲劳，请立即停车休息。"
        case "harsh": return "危险，请立即停止危险驾驶行为！"
        case "medium": return "请注意，检测到中度疲劳，请尽快停车休息。"
        default: return "请注意，请把双手放回方向盘。"
        }
    }

    private func playTone(_ kind: String) {
        // 1005 = alarm, 1057/1103 = short acknowledgements.
        let soundId: SystemSoundID = kind == "gentle" ? 1103 : (kind == "medium" ? 1057 : 1005)
        AudioServicesPlaySystemSound(soundId)
        if kind == "high" || kind == "harsh" {
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }
    }

    private func speak(_ text: String, fast: Bool) {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-CN")
        utterance.rate = fast ? 0.56 : 0.5
        synthesizer.speak(utterance)
    }
}
