package com.wakedrive.mobile.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.hypot

data class FaceMetrics(
    val faceDetected: Boolean,
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val ear: Float? = null,
    val mar: Float? = null,
    val blinkScore: Float = 0f,
    val yawnScore: Float = 0f,
)

/**
 * MediaPipe Face Landmarker: face landmarks, blink and jaw-open blendshapes.
 * IMAGE mode is used so every CameraX frame is fully independent (no timestamp/tracking pitfalls).
 */
class FaceAnalyzer(context: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("models/face_landmarker.task")
                    .setDelegate(Delegate.CPU)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .build()
    )

    fun analyze(bitmap: Bitmap): FaceMetrics {
        return try {
            val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
            val landmarks = result.faceLandmarks().firstOrNull()
            if (landmarks == null || landmarks.size <= 386) {
                return FaceMetrics(faceDetected = false)
            }
            val categories = result.faceBlendshapes().orElse(null)?.firstOrNull().orEmpty()
            fun score(name: String): Float = categories.firstOrNull {
                it.categoryName() == name || it.displayName() == name
            }?.score()?.coerceIn(0f, 1f) ?: 0f

            fun distance(a: Int, b: Int): Float {
                val dx = landmarks[a].x() - landmarks[b].x()
                val dy = landmarks[a].y() - landmarks[b].y()
                return hypot(dx, dy)
            }

            fun eyeAr(horizontal: IntArray, vertical: Array<IntArray>): Float? {
                val width = distance(horizontal[0], horizontal[1])
                if (width <= 1e-6f) return null
                val height = vertical.sumOf { distance(it[0], it[1]).toDouble() }.toFloat() / vertical.size
                return height / width
            }

            val leftEar = eyeAr(
                intArrayOf(33, 133),
                arrayOf(intArrayOf(159, 145), intArrayOf(158, 153)),
            )
            val rightEar = eyeAr(
                intArrayOf(362, 263),
                arrayOf(intArrayOf(386, 374), intArrayOf(385, 380)),
            )
            val ear = if (leftEar == null || rightEar == null) null else (leftEar + rightEar) / 2f
            val mouthWidth = distance(78, 308)
            val mar = if (mouthWidth <= 1e-6f) null else distance(13, 14) / mouthWidth

            val xs = landmarks.map { it.x() }
            val ys = landmarks.map { it.y() }
            val minX = xs.min()
            val maxX = xs.max()
            val minY = ys.min()
            val maxY = ys.max()

            FaceMetrics(
                faceDetected = true,
                left = minX,
                top = minY,
                width = maxX - minX,
                height = maxY - minY,
                ear = ear,
                mar = mar,
                blinkScore = (score("eyeBlinkLeft") + score("eyeBlinkRight")) / 2f,
                yawnScore = score("jawOpen"),
            )
        } catch (error: Exception) {
            FaceMetrics(faceDetected = false)
        }
    }

    override fun close() {
        landmarker.close()
    }
}
