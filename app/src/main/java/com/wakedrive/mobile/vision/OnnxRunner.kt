package com.wakedrive.mobile.vision

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Loads one ONNX asset into a single ORT session.
 * The model file is copied into filesDir once so multi-megabyte weights never sit in the Java heap.
 */
class OnnxRunner(context: Context, assetPath: String) : AutoCloseable {
    private val session: OrtSession

    init {
        val env = OrtEnvironment.getEnvironment()
        val target = File(context.filesDir, assetPath.replace('/', '_'))
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
        }
        session = env.createSession(target.absolutePath, OrtSession.SessionOptions())
    }

    private val inputName: String get() = session.inputNames.first()

    fun run(input: FloatArray, dims: LongArray): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), dims).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val buffer = output.floatBuffer
                val data = FloatArray(buffer.remaining())
                buffer.get(data)
                return data
            }
        }
    }

    override fun close() {
        session.close()
    }
}
