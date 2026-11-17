package com.wakedrive.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Draws the tracked face box on top of the camera preview. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#42D9F5")
    }

    private var left = 0f
    private var top = 0f
    private var width = 0f
    private var height = 0f
    private var sourceWidth = 3f
    private var sourceHeight = 4f
    private var mirrored = true
    private var visible = false

    fun setFace(
        normLeft: Float,
        normTop: Float,
        normWidth: Float,
        normHeight: Float,
        mirrored: Boolean,
        srcWidth: Int = 3,
        srcHeight: Int = 4,
    ) {
        left = normLeft
        top = normTop
        width = normWidth
        height = normHeight
        this.mirrored = mirrored
        sourceWidth = srcWidth.toFloat().coerceAtLeast(1f)
        sourceHeight = srcHeight.toFloat().coerceAtLeast(1f)
        visible = true
        invalidate()
    }

    fun clearFace() {
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return
        // PreviewView uses FILL_CENTER: scale to cover and center-crop.
        val sourceRatio = sourceWidth / sourceHeight
        val viewRatio = widthView() / heightView()
        val scaleX: Float
        val scaleY: Float
        if (viewRatio > sourceRatio) {
            scaleY = heightView()
            scaleX = heightView() * sourceRatio
        } else {
            scaleX = widthView()
            scaleY = widthView() / sourceRatio
        }
        val offsetX = (widthView() - scaleX) / 2f
        val offsetY = (heightView() - scaleY) / 2f
        val x = if (mirrored) 1f - left - width else left
        val rect = RectF(
            offsetX + x * scaleX,
            offsetY + top * scaleY,
            offsetX + (x + width) * scaleX,
            offsetY + (top + height) * scaleY,
        )
        canvas.drawRoundRect(rect, 18f, 18f, paint)
    }

    private fun widthView() = width.toFloat().coerceAtLeast(1f)
    private fun heightView() = height.toFloat().coerceAtLeast(1f)
}
