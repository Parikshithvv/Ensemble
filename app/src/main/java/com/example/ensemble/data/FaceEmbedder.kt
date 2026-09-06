package com.example.ensemble.data

import android.content.Context
import android.graphics.*
import android.util.Log
import com.example.ensemble.domain.DataError
import com.example.ensemble.domain.FaceInstance
import com.example.ensemble.domain.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "FaceEmbedder"

private const val MODEL_FILENAME = "mobilefacenet.tflite"
private const val INPUT_SIZE = 112
private const val EMBEDDING_SIZE = 192

// Standard ArcFace / MobileFaceNet 112x112 target eye coordinates (5-point alignment convention)
private const val TARGET_LEFT_EYE_X = 38.2946f
private const val TARGET_LEFT_EYE_Y = 51.6963f
private const val TARGET_RIGHT_EYE_X = 73.5318f
private const val TARGET_RIGHT_EYE_Y = 51.5014f

class FaceEmbedder(private val context: Context) {

    private var interpreter: Interpreter? = null

    init {
        try {
            Log.i(TAG, "Attempting to load $MODEL_FILENAME from assets...")
            val modelBuffer = loadModelFile(context, MODEL_FILENAME)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "SUCCESS: MobileFaceNet TFLite model loaded successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Failed to load $MODEL_FILENAME: ${e.message}", e)
        }
    }

    private fun loadModelFile(context: Context, modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Generate 192-d L2-normalized MobileFaceNet embedding for [faceInstance].
     * Uses 2-eye similarity transform alignment (rotation, scale, translation) to map eyes to standard 112x112 coordinates.
     */
    suspend fun generateEmbedding(faceInstance: FaceInstance): Result<FloatArray, DataError.Processing> =
        withContext(Dispatchers.Default) {
            val currentInterpreter = interpreter
            if (currentInterpreter == null) {
                Log.e(TAG, "Interpreter is NULL! Cannot generate embedding @${faceInstance.timestampMs}ms")
                return@withContext Result.Error(DataError.Processing.INFERENCE_FAILED)
            }

            try {
                val fullFrame = faceInstance.fullFrame
                val leftEye = faceInstance.leftEyePos
                val rightEye = faceInstance.rightEyePos

                // Create 112x112 aligned bitmap
                val alignedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(alignedBitmap)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

                if (leftEye != null && rightEye != null) {
                    // Eye-based similarity transform alignment
                    val src = floatArrayOf(leftEye.x, leftEye.y, rightEye.x, rightEye.y)
                    val dst = floatArrayOf(
                        TARGET_LEFT_EYE_X, TARGET_LEFT_EYE_Y,
                        TARGET_RIGHT_EYE_X, TARGET_RIGHT_EYE_Y
                    )

                    val matrix = Matrix()
                    matrix.setPolyToPoly(src, 0, dst, 0, 2)

                    canvas.drawBitmap(fullFrame, matrix, paint)

                    val deltaX = (rightEye.x - leftEye.x).toDouble()
                    val deltaY = (rightEye.y - leftEye.y).toDouble()
                    val rollAngle = Math.toDegrees(atan2(deltaY, deltaX))
                    val interEyeDist = hypot(deltaX, deltaY)

                    Log.d(
                        TAG,
                        "Aligning face @${faceInstance.timestampMs}ms: eyeL=(%.1f, %.1f) eyeR=(%.1f, %.1f) rollAngle=%.1f° interEyeDist=%.1f"
                            .format(leftEye.x, leftEye.y, rightEye.x, rightEye.y, rollAngle, interEyeDist)
                    )
                } else {
                    // Fallback: rotate around box center by roll angle and crop
                    val box = faceInstance.boundingBox
                    val roll = faceInstance.headEulerAngleZ
                    val marginX = box.width() * 0.15f
                    val marginY = box.height() * 0.15f

                    val cropLeft = max(0f, box.left - marginX)
                    val cropTop = max(0f, box.top - marginY)
                    val cropRight = min(fullFrame.width.toFloat(), box.right + marginX)
                    val cropBottom = min(fullFrame.height.toFloat(), box.bottom + marginY)

                    val src = floatArrayOf(
                        cropLeft, cropTop,
                        cropRight, cropTop
                    )
                    val dst = floatArrayOf(
                        0f, 0f,
                        INPUT_SIZE.toFloat(), 0f
                    )

                    val matrix = Matrix()
                    matrix.setPolyToPoly(src, 0, dst, 0, 2)

                    canvas.drawBitmap(fullFrame, matrix, paint)

                    Log.d(
                        TAG,
                        "Aligning face @${faceInstance.timestampMs}ms (fallback crop): rollAngle=%.1f°"
                            .format(roll)
                    )
                }

                // Convert 112x112 aligned bitmap to ByteBuffer (1 x 112 x 112 x 3 Float32 normalized to [-1, 1])
                val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
                    order(ByteOrder.nativeOrder())
                }

                val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
                alignedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
                alignedBitmap.recycle()

                for (pixel in pixels) {
                    val r = (Color.red(pixel) - 127.5f) / 127.5f
                    val g = (Color.green(pixel) - 127.5f) / 127.5f
                    val b = (Color.blue(pixel) - 127.5f) / 127.5f
                    inputBuffer.putFloat(r)
                    inputBuffer.putFloat(g)
                    inputBuffer.putFloat(b)
                }
                inputBuffer.rewind()

                // Run TFLite inference
                val outputArray = Array(1) { FloatArray(EMBEDDING_SIZE) }
                currentInterpreter.run(inputBuffer, outputArray)

                val rawVector = outputArray[0]

                // L2 Normalize output vector
                var sumSq = 0f
                for (v in rawVector) {
                    sumSq += v * v
                }
                val norm = sqrt(sumSq.toDouble()).toFloat()
                if (norm > 0f) {
                    for (i in rawVector.indices) {
                        rawVector[i] /= norm
                    }
                }

                Log.d(
                    TAG,
                    "Generated aligned 192-d embedding @${faceInstance.timestampMs}ms (norm before=%.3f, after=1.000)"
                        .format(norm)
                )

                Result.Success(rawVector)
            } catch (e: Exception) {
                Log.e(TAG, "Embedding extraction failed: ${e.message}", e)
                Result.Error(DataError.Processing.INFERENCE_FAILED)
            }
        }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
