package com.wakedrive.mobile.ui.reports

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import org.json.JSONArray

/** Draws the monitoring report risk curve (score over time) from report samples. */
class RiskCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var samples: JSONArray? = null
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#42D9F5")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#25455788")
    }

    fun setSamples(array: JSONArray?) {
        samples = array
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val array = samples ?: return
        if (array.length() < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        for (level in 30..90 step 30) {
            val y = h - level / 100f * h
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        var duration = 0L
        for (i in 0 until array.length()) {
            val sample = array.optJSONObject(i) ?: continue
            duration = maxOf(duration, sample.optLong("offsetMs") + sample.optLong("spanMs"))
        }
        if (duration <= 0) return
        val path = Path()
        var started = false
        for (i in 0 until array.length()) {
            val sample = array.optJSONObject(i) ?: continue
            if (sample.optLong("validMs") <= 0 || sample.isNull("score")) {
                started = false
                continue
            }
            val x = sample.optLong("offsetMs") / duration.toFloat() * w
            val y = h - (sample.optInt("score") / 100f).coerceIn(0f, 1f) * h
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, linePaint)
    }
}
