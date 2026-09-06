package com.example.ensemble.data

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.example.ensemble.domain.DataError
import com.example.ensemble.domain.FaceInstance
import com.example.ensemble.domain.Result
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val TAG = "MlKitFaceDetector"

// Minimum face area fraction (0.1% of total frame area)
private const val MIN_FACE_FRACTION = 0.001f

class MlKitFaceDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
    )

    init {
        Log.i(TAG, "MlKitFaceDetector initialized with LANDMARK_MODE_ALL.")
    }

    /**
     * Run ML Kit face detection directly using InputImage.fromBitmap(bitmap, 0).
     * Ensures bitmap is in software ARGB_8888 config (not GPU HARDWARE config).
     */
    suspend fun detect(
        bitmap: Bitmap,
        timestampMs: Long,
        videoUri: String
    ): Result<List<FaceInstance>, DataError.Processing> = withContext(Dispatchers.Default) {
        Log.d(TAG, "detect() called for frame @${timestampMs}ms")
        try {
            // Ensure bitmap is a CPU software bitmap (HARDWARE bitmaps fail in ML Kit)
            val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                Log.w(TAG, "Converting HARDWARE bitmap to ARGB_8888 software bitmap for ML Kit...")
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            // Directly construct InputImage from Bitmap
            val image = InputImage.fromBitmap(softwareBitmap, 0)
            val faces: List<Face> = detector.process(image).await()

            Log.d(TAG, "ML Kit raw faces count = ${faces.size} at frame @${timestampMs}ms (${softwareBitmap.width}x${softwareBitmap.height})")

            val frameArea = softwareBitmap.width.toFloat() * softwareBitmap.height.toFloat()
            val instances = faces.mapNotNull { face ->
                val box = face.boundingBox

                // Clamp bounding box to bitmap boundaries
                val left = max(0f, box.left.toFloat())
                val top = max(0f, box.top.toFloat())
                val right = min(softwareBitmap.width.toFloat(), box.right.toFloat())
                val bottom = min(softwareBitmap.height.toFloat(), box.bottom.toFloat())

                if (right <= left || bottom <= top) {
                    Log.w(TAG, "Discarding face with invalid clamped bounds: left=$left top=$top right=$right bottom=$bottom")
                    return@mapNotNull null
                }

                val rectF = RectF(left, top, right, bottom)
                val faceArea = rectF.width() * rectF.height()

                if (faceArea / frameArea < MIN_FACE_FRACTION) {
                    Log.d(TAG, "Filtering face @${timestampMs}ms - too small (fraction=${faceArea/frameArea})")
                    return@mapNotNull null
                }

                val yaw = face.headEulerAngleY
                val pitch = face.headEulerAngleX
                val roll = face.headEulerAngleZ
                val smiling = face.smilingProbability ?: 0.5f
                val leftEyeOpen = face.leftEyeOpenProbability ?: 0.5f
                val rightEyeOpen = face.rightEyeOpenProbability ?: 0.5f

                val leftEyeLandmark = face.getLandmark(FaceLandmark.LEFT_EYE)
                val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)

                val leftEyePos = leftEyeLandmark?.position?.let { PointF(it.x, it.y) }
                val rightEyePos = rightEyeLandmark?.position?.let { PointF(it.x, it.y) }

                val sharpness = FaceQualityScorer.computeSharpness(softwareBitmap, rectF)
                val score = FaceQualityScorer.qualityScore(
                    yaw, pitch, sharpness, leftEyeOpen, rightEyeOpen, smiling
                )

                Log.d(
                    TAG,
                    "Detected face @${timestampMs}ms [${rectF.left.toInt()},${rectF.top.toInt()},${rectF.right.toInt()},${rectF.bottom.toInt()}] " +
                            "yaw=%.1f pitch=%.1f roll=%.1f eyeL=${leftEyePos?.let { "(%.1f,%.1f)".format(it.x, it.y) } ?: "null"} " +
                            "eyeR=${rightEyePos?.let { "(%.1f,%.1f)".format(it.x, it.y) } ?: "null"} sharp=%.1f score=%.3f"
                                .format(yaw, pitch, roll, sharpness, score)
                )

                FaceInstance(
                    videoUri = videoUri,
                    timestampMs = timestampMs,
                    boundingBox = rectF,
                    headEulerAngleX = pitch,
                    headEulerAngleY = yaw,
                    headEulerAngleZ = roll,
                    smilingProbability = smiling,
                    leftEyeOpenProbability = leftEyeOpen,
                    rightEyeOpenProbability = rightEyeOpen,
                    sharpness = sharpness,
                    qualityScore = score,
                    fullFrame = softwareBitmap,
                    leftEyePos = leftEyePos,
                    rightEyePos = rightEyePos
                )
            }
            Log.d(TAG, "${instances.size} valid face(s) out of ${faces.size} raw faces at ${timestampMs}ms")
            Result.Success(instances)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed on frame @${timestampMs}ms", e)
            Result.Error(DataError.Processing.INFERENCE_FAILED)
        }
    }

    fun close() = detector.close()
}
