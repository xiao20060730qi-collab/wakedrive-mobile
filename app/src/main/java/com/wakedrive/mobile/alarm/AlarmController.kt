package com.wakedrive.mobile.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Graduated audible alerts:
 *  - high/harsh: loud piercing double-beep loop (severe fatigue, phone use, face lost)
 *  - medium: standard beep loop at the configured cooldown
 *  - gentle: single soft acknowledgement for drinking reminders
 */
class AlarmController(context: Context) : TextToSpeech.OnInitListener, AutoCloseable {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech = TextToSpeech(appContext, this)
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var level = "none"
    private var looping = false
    private var repeatMs = 5000L
    private var enabled = true
    private var silentStyle = false
    private var mediumCooldownMs = 5000L

    fun configure(enabled: Boolean, style: String, cooldownMs: Int) {
        this.enabled = enabled
        silentStyle = style == "silent"
        mediumCooldownMs = cooldownMs.toLong().coerceIn(100, 60_000)
        if (!enabled || silentStyle) stop()
    }

    init {
        tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (error: RuntimeException) {
            null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINA)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun speak(text: String, rate: Float = 0.95f) {
        if (!ttsReady || text.isBlank()) return
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wakedrive-alarm")
    }

    private fun beep(kind: String) {
        val generator = tone ?: return
        val toneType = when (kind) {
            "high", "harsh" -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            "gentle" -> ToneGenerator.TONE_PROP_ACK
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        val duration = if (kind == "high" || kind == "harsh") 700 else 350
        try {
            generator.startTone(toneType, duration)
        } catch (error: RuntimeException) {
            // Some devices refuse tone playback; speech still fires.
        }
    }

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!looping) return
            beep(level)
            if (level == "high" || level == "harsh") {
                speak(defaultVoice(level), rate = 1.05f)
            }
            handler.postDelayed(this, repeatMs)
        }
    }

    private fun defaultVoice(level: String): String = when (level) {
        "high" -> "危险，检测到重度疲劳，请立即停车休息。"
        "harsh" -> "危险，请立即停止危险驾驶行为！"
        "medium" -> "请注意，检测到中度疲劳，请尽快停车休息。"
        else -> "请注意，请把双手放回方向盘。"
    }

    fun update(nextLevel: String, message: String? = null) {
        if (!enabled || silentStyle) return
        val normalized = if (nextLevel in setOf("high", "harsh", "medium")) nextLevel else "none"
        if (normalized == "none") {
            stop()
            return
        }
        if (normalized == level && looping) return
        stop()
        level = normalized
        looping = true
        repeatMs = when (normalized) {
            "high" -> 900L
            "harsh" -> 700L
            else -> mediumCooldownMs
        }
        beep(level)
        speak(message ?: defaultVoice(level), rate = if (level == "medium") 0.95f else 1.05f)
        handler.postDelayed(loopRunnable, repeatMs)
    }

    /** One-shot alert; message == null plays a tone only. */
    fun event(kind: String, message: String?) {
        if (!enabled || silentStyle) return
        val normalized = if (kind in setOf("high", "harsh", "medium", "gentle")) kind else "medium"
        beep(normalized)
        if (message != null) speak(message, rate = if (normalized == "gentle") 0.95f else 1.05f)
    }

    fun stop() {
        looping = false
        level = "none"
        handler.removeCallbacks(loopRunnable)
        try {
            tone?.stopTone()
        } catch (error: RuntimeException) {
            // ignore
        }
        tts.stop()
    }

    override fun close() {
        stop()
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        tts.shutdown()
    }
}
