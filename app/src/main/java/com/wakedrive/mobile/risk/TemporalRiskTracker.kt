package com.wakedrive.mobile.risk

import kotlin.math.max
import kotlin.math.min

data class RiskResult(
    val faceDetected: Boolean,
    val score: Int,
    val severity: String,
    val risk: String,
    val reasons: List<String>,
    val triggerType: String?,
    val closedDurationMs: Long,
    val yawnDurationMs: Long,
    val closedRatio: Float?,
    val windowValidMs: Long,
    val ear: Float?,
    val mar: Float?,
    val eyeClosed: Boolean,
    val mouthOpen: Boolean,
    val blinkScore: Float,
    val yawnScore: Float,
)

/**
 * Sliding-window eye closure / mouth opening tracker.
 * Ported from the web client, including the glasses guard: a low EAR only counts
 * as a closed eye when the blink blendshape agrees.
 */
class TemporalRiskTracker(
    private val earThreshold: Float = 0.25f,
    private val marThreshold: Float = 0.55f,
    private val mildFrames: Int = 2,
    private val mediumFrames: Int = 5,
    private val heavyFrames: Int = 9,
    private val detectIntervalMs: Int = 300,
) {
    private data class Segment(val start: Long, val end: Long, val valid: Boolean, val closed: Boolean)

    private var previousTime = -1L
    private var previousFace = false
    private var previousClosed = false
    private var eyeStart = -1L
    private var mouthStart = -1L
    private val segments = ArrayDeque<Segment>()

    fun reset() {
        previousTime = -1L
        previousFace = false
        previousClosed = false
        eyeStart = -1L
        mouthStart = -1L
        segments.clear()
    }

    fun update(
        faceDetected: Boolean,
        ear: Float?,
        mar: Float?,
        blinkScore: Float,
        yawnScore: Float,
        now: Long,
    ): RiskResult {
        if (previousTime >= 0 && now < previousTime) reset()

        val blinkEvidence = blinkScore >= 0.35f
        val eyeClosed = faceDetected && if (ear != null) {
            ear < earThreshold && blinkEvidence
        } else {
            blinkScore >= 0.58f
        }
        val mouthOpen = faceDetected && if (mar != null) {
            mar > marThreshold
        } else {
            yawnScore >= 0.65f
        }

        val delta = if (previousTime < 0) 0L else now - previousTime
        val continuous = previousTime >= 0 && delta in 0..1500 && previousFace && faceDetected
        if (previousTime >= 0 && delta > 0) {
            segments.addLast(Segment(previousTime, now, continuous, continuous && previousClosed))
        }
        if (!continuous) {
            eyeStart = -1L
            mouthStart = -1L
        }
        if (eyeClosed) {
            if (eyeStart < 0) eyeStart = now
        } else {
            eyeStart = -1L
        }
        if (mouthOpen) {
            if (mouthStart < 0) mouthStart = now
        } else {
            mouthStart = -1L
        }

        val cutoff = now - 60_000
        while (segments.isNotEmpty() && segments.first().end <= cutoff) segments.removeFirst()
        var validMs = 0L
        var closedMs = 0L
        for (segment in segments) {
            val span = segment.end - max(cutoff, segment.start)
            if (segment.valid) validMs += span
            if (segment.closed) closedMs += span
        }

        val closedDuration = if (eyeStart < 0) 0L else now - eyeStart
        val yawnDuration = if (mouthStart < 0) 0L else now - mouthStart
        val closedRatio = if (validMs >= 10_000) closedMs.toFloat() / validMs else null

        val mild = max(300f, mildFrames * detectIntervalMs.toFloat()).toLong()
        val medium = maxOf(700L, mild + 100L, (mediumFrames * detectIntervalMs).toLong())
        val high = maxOf(1200L, medium + 100L, (heavyFrames * detectIntervalMs).toLong())
        val eyeLimits = longArrayOf(mild, medium, high)
        val mouthLimits = longArrayOf(
            max(1500f, mild.toFloat()).toLong(),
            max(2500f, medium.toFloat()).toLong(),
            max(4000f, high.toFloat()).toLong(),
        )

        var eyeLevel = 0
        var mouthLevel = 0
        for (i in 0..2) {
            if (closedDuration >= eyeLimits[i]) eyeLevel = i + 1
            if (yawnDuration >= mouthLimits[i]) mouthLevel = i + 1
        }

        val reasons = mutableListOf<String>()
        if (closedDuration >= eyeLimits[0]) {
            reasons.add("持续闭眼 ${"%.1f".format(closedDuration / 1000f)} 秒")
        }
        if (yawnDuration >= mouthLimits[0]) {
            reasons.add("持续张嘴 ${"%.1f".format(yawnDuration / 1000f)} 秒")
        }
        if (validMs >= 10_000 && closedRatio != null) {
            val ratioLimits = floatArrayOf(0.15f, 0.25f, 0.4f)
            for (i in 0..2) if (closedRatio >= ratioLimits[i]) eyeLevel = max(eyeLevel, i + 1)
            if (closedRatio >= 0.15f) {
                reasons.add("近 60 秒有效检测中闭眼占比 ${(closedRatio * 100).toInt()}%")
            }
        }

        val level = max(eyeLevel, mouthLevel)
        val score = intArrayOf(0, 30, 60, 90)[level]
        val severities = arrayOf("normal", "mild", "medium", "high")
        val risks = arrayOf("正常", "轻度关注", "中度风险", "高风险")
        val trigger = when {
            level == 0 -> null
            mouthLevel > eyeLevel -> "YAWN"
            else -> "EYE_CLOSE"
        }

        previousTime = now
        previousFace = faceDetected
        previousClosed = eyeClosed

        return RiskResult(
            faceDetected = faceDetected,
            score = score,
            severity = severities[level],
            risk = risks[level],
            reasons = reasons,
            triggerType = trigger,
            closedDurationMs = closedDuration,
            yawnDurationMs = yawnDuration,
            closedRatio = closedRatio,
            windowValidMs = validMs,
            ear = ear,
            mar = mar,
            eyeClosed = eyeClosed,
            mouthOpen = mouthOpen,
            blinkScore = blinkScore,
            yawnScore = yawnScore,
        )
    }
}
