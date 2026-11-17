package com.wakedrive.mobile.risk

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Session recorder compatible with the Python `/reports` contract:
 * one-second buckets with merged severity, plus the event list.
 * Ported from the web session-recorder.js.
 */
class SessionRecorder(
    private val source: String = "camera",
    private val title: String = "手机端监测",
    private val settings: JSONObject = JSONObject(),
) {
    private data class Sample(
        var offsetMs: Long,
        var spanMs: Long,
        var validMs: Long,
        var closedMs: Long,
        var score: Int?,
        var severity: String,
        var reasons: List<String>,
    )

    private data class RecEvent(
        val offsetMs: Long,
        val type: String,
        val severity: String,
        val reason: String,
        val ear: Float?,
        val mar: Float?,
        val closedDurationMs: Long?,
        val yawnDurationMs: Long?,
    )

    private data class Metrics(
        val valid: Boolean,
        val eyeClosed: Boolean,
        val score: Int?,
        val severity: String,
        val reasons: List<String>,
    )

    val clientId: String = UUID.randomUUID().toString()
    private var startedAt = 0L
    private var lastOffset = 0L
    private var ended = false
    private var previous: Metrics? = null
    private val samples = mutableListOf<Sample>()
    private val events = mutableListOf<RecEvent>()

    fun start(now: Long = System.currentTimeMillis()) {
        if (startedAt == 0L) startedAt = now
    }

    fun add(
        faceDetected: Boolean,
        eyeClosed: Boolean,
        score: Int,
        severity: String,
        reasons: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (startedAt == 0L) start(now)
        if (ended) return
        val offset = (now - startedAt).coerceIn(0, 86_400_000)
        if (offset < lastOffset) return
        val valid = faceDetected
        val known = Metrics(
            valid = valid,
            eyeClosed = valid && eyeClosed,
            score = if (valid) score.coerceIn(0, 100) else null,
            severity = if (!valid) "none" else severity.takeIf { it in setOf("normal", "mild", "medium", "high") } ?: "normal",
            reasons = if (valid) cleanReasons(reasons) else emptyList(),
        )
        val continueValid = previous?.valid == true && known.valid && offset - lastOffset <= 1500
        val interval = if (continueValid) {
            val prior = previous!!
            Metrics(
                valid = true,
                eyeClosed = prior.eyeClosed,
                score = maxOf(prior.score ?: 0, known.score ?: 0),
                severity = worse(prior.severity, known.severity),
                reasons = cleanReasons(prior.reasons + known.reasons),
            )
        } else {
            Metrics(false, false, null, "none", emptyList())
        }
        appendInterval(lastOffset, offset, interval)
        lastOffset = offset
        previous = known
    }

    fun addEvent(
        type: String,
        severityChinese: String,
        reason: String,
        ear: Float?,
        mar: Float?,
        closedDurationMs: Long?,
        yawnDurationMs: Long?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (startedAt == 0L) start(now)
        if (ended) return
        if (type !in setOf("EYE_CLOSE", "YAWN", "PHONE_USE", "HEAD_DOWN", "HEAD_AWAY", "FACE_LOST", "DRINK")) return
        val severity = when (severityChinese) {
            "重度疲劳" -> "high"
            "中度疲劳" -> "medium"
            else -> "mild"
        }
        val offset = (now - startedAt).coerceIn(0, 86_400_000)
        events.add(
            RecEvent(
                offsetMs = offset,
                type = type,
                severity = severity,
                reason = reason.take(200),
                ear = ear?.takeIf { it in 0f..2f },
                mar = mar?.takeIf { it in 0f..5f },
                closedDurationMs = closedDurationMs?.coerceIn(0, 600_000),
                yawnDurationMs = yawnDurationMs?.coerceIn(0, 600_000),
            ),
        )
    }

    fun finish(now: Long = System.currentTimeMillis()): JSONObject {
        if (startedAt == 0L) start(now)
        ended = true
        val stop = maxOf(lastOffset, (now - startedAt).coerceIn(0, 86_400_000))
        if (stop > lastOffset) {
            val tail = previous?.takeIf { it.valid && stop - lastOffset <= 1500 }
                ?: Metrics(false, false, null, "none", emptyList())
            appendInterval(lastOffset, stop, tail)
            lastOffset = stop
        }
        return JSONObject().apply {
            put("clientId", clientId)
            put("source", source)
            put("title", title.take(120))
            put("startedAt", Instant.ofEpochMilli(startedAt).toString())
            put("endedAt", Instant.ofEpochMilli(startedAt + stop).toString())
            put("samples", JSONArray().also { array ->
                samples.forEach { sample ->
                    array.put(JSONObject().apply {
                        put("offsetMs", sample.offsetMs)
                        put("spanMs", sample.spanMs)
                        put("validMs", sample.validMs)
                        put("closedMs", sample.closedMs)
                        put("score", sample.score ?: JSONObject.NULL)
                        put("severity", sample.severity)
                        put("reasons", JSONArray(sample.reasons))
                    })
                }
            })
            put("events", JSONArray().also { array ->
                events.forEach { event ->
                    array.put(JSONObject().apply {
                        put("offsetMs", event.offsetMs)
                        put("type", event.type)
                        put("severity", event.severity)
                        put("reason", event.reason)
                        put("ear", event.ear?.toDouble() ?: JSONObject.NULL)
                        put("mar", event.mar?.toDouble() ?: JSONObject.NULL)
                        put("closedDurationMs", event.closedDurationMs ?: JSONObject.NULL)
                        put("yawnDurationMs", event.yawnDurationMs ?: JSONObject.NULL)
                    })
                }
            })
            put("settings", JSONObject().apply {
                listOf("earThreshold", "marThreshold", "mildFrames", "mediumFrames", "heavyFrames", "detectIntervalMs")
                    .forEach { key -> if (settings.has(key)) put(key, settings.opt(key)) }
            })
        }
    }

    val eventCount: Int get() = events.size

    private fun appendInterval(fromValue: Long, toValue: Long, metrics: Metrics) {
        var from = fromValue
        while (from < toValue) {
            val bucketEnd = minOf(toValue, (from / 1000 + 1) * 1000)
            val span = bucketEnd - from
            val bucket = Sample(
                offsetMs = from,
                spanMs = span,
                validMs = if (metrics.valid) span else 0,
                closedMs = if (metrics.valid && metrics.eyeClosed) span else 0,
                score = metrics.score,
                severity = metrics.severity,
                reasons = metrics.reasons,
            )
            val last = samples.lastOrNull()
            if (last != null && last.offsetMs + last.spanMs == from && last.offsetMs / 1000 == from / 1000) {
                samples[samples.size - 1] = merge(last, bucket)
            } else {
                samples.add(bucket)
            }
            if (samples.size > 2000) {
                val compacted = mutableListOf<Sample>()
                var index = 0
                while (index < samples.size) {
                    compacted.add(if (index + 1 < samples.size) merge(samples[index], samples[index + 1]) else samples[index])
                    index += 2
                }
                samples.clear()
                samples.addAll(compacted)
            }
            from = bucketEnd
        }
    }

    private fun merge(a: Sample, b: Sample): Sample {
        val worst = if ((b.score ?: -1) > (a.score ?: -1)) b else a
        return Sample(
            offsetMs = a.offsetMs,
            spanMs = a.spanMs + b.spanMs,
            validMs = a.validMs + b.validMs,
            closedMs = a.closedMs + b.closedMs,
            score = worst.score,
            severity = worst.severity,
            reasons = cleanReasons(worst.reasons + a.reasons + b.reasons),
        )
    }

    private fun cleanReasons(values: List<String>): List<String> {
        return values.filter { it.isNotBlank() }.map { it.take(200) }.distinct().take(5)
    }

    private fun worse(a: String, b: String): String {
        val order = listOf("none", "normal", "mild", "medium", "high")
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }
}
