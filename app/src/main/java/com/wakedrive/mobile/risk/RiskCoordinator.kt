package com.wakedrive.mobile.risk

data class RiskEvent(
    val type: String,
    val severity: String,
    val earValue: Float,
    val marValue: Float,
    val headPose: String,
    val message: String,
)

/**
 * Event de-duplication, cooldown and yawn-frequency escalation.
 * Mirrors the web coordinator: >=2 yawns within 3 minutes upgrades the alert to medium.
 */
class RiskCoordinator(private val cooldownMs: Long = 5000) {
    private var lastSeverity = "none"
    private var lastEventAt = 0L
    private val yawnTimes = ArrayDeque<Long>()

    fun reset() {
        lastSeverity = "none"
        lastEventAt = 0L
        yawnTimes.clear()
    }

    fun handle(result: RiskResult, now: Long): RiskEvent? {
        if (!result.faceDetected || result.severity !in setOf("mild", "medium", "high")) {
            reset()
            return null
        }
        if (result.severity == lastSeverity && now - lastEventAt < cooldownMs) return null

        lastSeverity = result.severity
        lastEventAt = now

        val reasons = result.reasons
        val eyeEvidence = reasons.any { it.contains("持续闭眼") || it.contains("闭眼占比") }
        val mouthEvidence = reasons.any { it.contains("持续张嘴") }
        val type = when {
            result.triggerType == "EYE_CLOSE" || result.triggerType == "YAWN" -> result.triggerType
            mouthEvidence && !eyeEvidence -> "YAWN"
            eyeEvidence -> "EYE_CLOSE"
            result.yawnScore > result.blinkScore && result.closedDurationMs == 0L -> "YAWN"
            else -> "EYE_CLOSE"
        }

        var severity = when (result.severity) {
            "high" -> "重度疲劳"
            "medium" -> "中度疲劳"
            else -> "轻度疲劳"
        }
        var message = reasons.joinToString("；").ifEmpty {
            if (result.severity == "high") "检测到重度疲劳，请在安全地点停车休息。"
            else "检测到疲劳风险，请关注状态并安排休息。"
        }

        if (type == "YAWN") {
            yawnTimes.addLast(now)
            while (yawnTimes.isNotEmpty() && now - yawnTimes.first() >= 180_000) yawnTimes.removeFirst()
            if (yawnTimes.size >= 2) {
                if (severity != "重度疲劳") severity = "中度疲劳"
                message = "3 分钟内已打哈欠 ${yawnTimes.size} 次，$message"
                return RiskEvent(type, severity, result.ear ?: 0f, result.mar ?: 0f, "", message)
                    .also { lastEscalated = true }
            }
        }
        lastEscalated = false
        return RiskEvent(type, severity, result.ear ?: 0f, result.mar ?: 0f, "", message)
    }

    /** True when the event returned by [handle] was upgraded because of repeated yawns. */
    var lastEscalated: Boolean = false
        private set
}
