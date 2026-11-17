package com.wakedrive.mobile.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Letterboxed(
    val chw: FloatArray,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

object BitmapUtils {

    fun imageToBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        buffer.rewind()
        if (pixelStride == 4) {
            val rowWidth = stride / 4
            val padded = Bitmap.createBitmap(rowWidth, proxy.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            if (rowWidth == proxy.width) {
                return padded
            }
            val cropped = Bitmap.createBitmap(padded, 0, 0, proxy.width, proxy.height)
            padded.recycle()
            return cropped
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Letterbox into a square RGB float tensor (CHW, 0..1), gray padding like the web detector. */
    fun letterbox(bitmap: Bitmap, size: Int, padValue: Float = 0.337f): Letterboxed {
        val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val drawWidth = max(1, (bitmap.width * scale).roundToInt())
        val drawHeight = max(1, (bitmap.height * scale).roundToInt())
        val offsetX = (size - drawWidth) / 2f
        val offsetY = (size - drawHeight) / 2f

        val padded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.rgb(86, 86, 86))
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(offsetX, offsetY, offsetX + drawWidth, offsetY + drawHeight), null)

        val pixels = IntArray(size * size)
        padded.getPixels(pixels, 0, size, 0, 0, size, size)
        padded.recycle()
        val chw = FloatArray(3 * size * size)
        val plane = size * size
        for (i in pixels.indices) {
            val color = pixels[i]
            chw[i] = ((color shr 16) and 0xFF) / 255f
            chw[plane + i] = ((color shr 8) and 0xFF) / 255f
            chw[2 * plane + i] = (color and 0xFF) / 255f
        }
        return Letterboxed(chw, scale, offsetX, offsetY, bitmap.width, bitmap.height)
    }

    /** Crop a padded square around a normalized face box and produce a 224 BGR ImageNet-normalized tensor. */
    fun faceCropTensor(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        size: Int = 224,
        margin: Float = 0.35f,
    ): FloatArray? {
        val marginX = width * margin
        val marginY = height * margin
        val leftPx = ((left - marginX) * bitmap.width).roundToInt().coerceAtLeast(0)
        val topPx = ((top - marginY) * bitmap.height).roundToInt().coerceAtLeast(0)
        val rightPx = ((left + width + marginX) * bitmap.width).roundToInt().coerceAtMost(bitmap.width)
        val bottomPx = ((top + height + marginY) * bitmap.height).roundToInt().coerceAtMost(bitmap.height)
        val cropW = rightPx - leftPx
        val cropH = bottomPx - topPx
        if (cropW < 20 || cropH < 20) return null

        val scaled = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, leftPx, topPx, cropW, cropH), size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        scaled.recycle()

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val plane = size * size
        val out = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            // sixdrepnet360 consumes BGR with ImageNet normalization.
            out[i] = (b - mean[0]) / std[0]
            out[plane + i] = (g - mean[1]) / std[1]
            out[2 * plane + i] = (r - mean[2]) / std[2]
        }
        return out
    }

    fun jpegBase64(bitmap: Bitmap, maxWidth: Int = 320, quality: Int = 55): String? {
        return try {
            val scale = min(1f, maxWidth.toFloat() / bitmap.width)
            val width = max(1, (bitmap.width * scale).roundToInt())
            val height = max(1, (bitmap.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (scaled !== bitmap) scaled.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (error: Exception) {
            null
        }
    }
}
