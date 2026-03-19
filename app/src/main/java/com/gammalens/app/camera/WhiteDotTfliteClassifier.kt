package com.gammalens.app.camera

import android.content.Context
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhiteDotTfliteClassifier(
    context: Context,
    private val inputSize: Int = 64,
    modelAssetPath: String = "models/white_dot_detector.tflite",
) {
    private val interpreter: Interpreter?
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(1) }

    init {
        interpreter = try {
            val model = context.assets.open(modelAssetPath).use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
            modelBuffer.put(model)
            modelBuffer.rewind()
            Interpreter(modelBuffer)
        } catch (e: Exception) {
            Log.w("GL_DEEP", "Deep model not loaded: ${e.message}")
            null
        }
    }

    fun isReady(): Boolean = interpreter != null

    fun infer(grayMat: Mat): Double? {
        val tflite = interpreter ?: return null
        if (grayMat.empty()) return null
        val resized = Mat()
        val floatMat = Mat()
        return try {
            Imgproc.resize(grayMat, resized, Size(inputSize.toDouble(), inputSize.toDouble()))
            resized.convertTo(floatMat, CvType.CV_32F, 1.0 / 255.0)
            inputBuffer.clear()
            val pixels = FloatArray(inputSize * inputSize)
            floatMat.get(0, 0, pixels)
            for (v in pixels) inputBuffer.putFloat(v)
            tflite.run(inputBuffer, output)
            output[0][0].toDouble().coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            Log.w("GL_DEEP", "Deep model inference failed: ${e.message}")
            null
        } finally {
            resized.release()
            floatMat.release()
        }
    }

    fun close() {
        interpreter?.close()
    }
}
