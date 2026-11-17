package com.wakedrive.mobile.vision

import android.content.Context
import android.graphics.Bitmap
import com.wakedrive.mobile.util.BitmapUtils
import kotlin.math.hypot

data class PhoneBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
)

data class PoseResult(
    val callingHeld: Boolean = false,
    val handToMouth: Boolean = false,
)

data class HeadPose(
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
)

/** YOLO11n COCO detector – class 67 is "cell phone". Output tensor [1,84,8400]. */
class PhoneDetector(context: Context) : AutoCloseable {
    private val runner = OnnxRunner(context, "models/yolo11n.onnx")
    private val anchors = 8400
    private val channels = 84
    private val phoneClass = 67
    private val scoreThreshold = 0.3f

    fun detect(bitmap: Bitmap): PhoneBox? {
        return try {
            val letterboxed = BitmapUtils.letterbox(bitmap, 640)
            val output = runner.run(letterboxed.chw, longArrayOf(1, 3, 640, 640))
            if (output.size < channels * anchors) return null
            var best: PhoneBox? = null
            for (anchor in 0 until anchors) {
                val confidence = output[(4 + phoneClass) * anchors + anchor]
                if (confidence < scoreThreshold) continue
                if (best != null && confidence <= best.confidence) continue
                val cx = output[anchor]
                val cy = output[anchors + anchor]
                val w = output[2 * anchors + anchor]
                val h = output[3 * anchors + anchor]
                best = PhoneBox(
                    centerX = (cx - letterboxed.offsetX) / letterboxed.scale,
                    centerY = (cy - letterboxed.offsetY) / letterboxed.scale,
                    width = w / letterboxed.scale,
                    height = h / letterboxed.scale,
                    confidence = confidence,
                )
            }
            best
        } catch (error: Exception) {
            null
        }
    }

    override fun close() = runner.close()
}

/**
 * YOLOv8n-pose – output tensor [1,56,8400] (4 box + 1 score + 17 keypoints x 3).
 * Wrist-to-ear distance detects a calling posture; wrist-to-nose detects drinking/lifting gestures.
 */
class PoseDetector(context: Context) : AutoCloseable {
    private val runner = OnnxRunner(context, "models/yolov8n-pose.onnx")
    private val anchors = 8400
    private val channels = 56

    fun detect(bitmap: Bitmap, phoneVisible: Boolean): PoseResult {
        return try {
            val letterboxed = BitmapUtils.letterbox(bitmap, 640)
            val output = runner.run(letterboxed.chw, longArrayOf(1, 3, 640, 640))
            if (output.size < channels * anchors) return PoseResult()

            var bestAnchor = -1
            var bestScore = 0f
            for (anchor in 0 until anchors) {
                val score = output[4 * anchors + anchor]
                if (score > bestScore) {
                    bestScore = score
                    bestAnchor = anchor
                }
            }
            if (bestAnchor < 0 || bestScore < 0.4f) return PoseResult()

            fun keypoint(index: Int): FloatArray {
                val base = (5 + index * 3) * anchors + bestAnchor
                val x = (output[base] - letterboxed.offsetX) / letterboxed.scale
                val y = (output[base + 1] - letterboxed.offsetY) / letterboxed.scale
                val confidence = output[base + 2]
                return floatArrayOf(x, y, confidence)
            }

            val nose = keypoint(0)
            val leftEar = keypoint(3)
            val rightEar = keypoint(4)
            val leftShoulder = keypoint(5)
            val rightShoulder = keypoint(6)
            val leftWrist = keypoint(9)
            val rightWrist = keypoint(10)

            val shoulderSpan = hypot(
                (rightShoulder[0] - leftShoulder[0]).toDouble(),
                (rightShoulder[1] - leftShoulder[1]).toDouble(),
            ).toFloat().coerceAtLeast(1f)

            fun near(wrist: FloatArray, target: FloatArray, threshold: Float): Boolean =
                wrist[2] > 0.25f && target[2] > 0.2f &&
                    hypot((wrist[0] - target[0]).toDouble(), (wrist[1] - target[1]).toDouble()) < threshold

            val earThreshold = shoulderSpan * 0.5f
            fun nearEar(wrist: FloatArray, ear: FloatArray): Boolean {
                if (!near(wrist, ear, earThreshold)) return false
                return wrist[1] < leftShoulder[1] || wrist[1] < rightShoulder[1]
            }
            val callingHeld = nearEar(leftWrist, leftEar) || nearEar(rightWrist, rightEar)

            val mouthThreshold = (shoulderSpan * 0.35f).coerceAtLeast(40f)
            val handToMouth = !phoneVisible &&
                (near(leftWrist, nose, mouthThreshold) || near(rightWrist, nose, mouthThreshold))

            PoseResult(callingHeld = callingHeld, handToMouth = handToMouth)
        } catch (error: Exception) {
            PoseResult()
        }
    }

    override fun close() = runner.close()
}

/** 6DRepNet360 – 224x224 BGR ImageNet-normalized crop; output yaw, pitch, roll in degrees. */
class HeadPoseEstimator(context: Context) : AutoCloseable {
    private val runner = OnnxRunner(context, "models/6drepnet360.onnx")

    fun estimate(bitmap: Bitmap, face: FaceMetrics): HeadPose? {
        if (!face.faceDetected) return null
        return try {
            val input = BitmapUtils.faceCropTensor(bitmap, face.left, face.top, face.width, face.height) ?: return null
            val output = runner.run(input, longArrayOf(1, 3, 224, 224))
            if (output.size < 3) return null
            HeadPose(yaw = output[0], pitch = output[1], roll = output[2])
        } catch (error: Exception) {
            null
        }
    }

    override fun close() = runner.close()
}
