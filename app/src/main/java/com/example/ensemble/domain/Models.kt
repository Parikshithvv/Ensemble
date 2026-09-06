package com.example.ensemble.domain

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

/**
 * One detected face in one sampled frame.
 * fullFrame: the full decoded video frame bitmap (not the tight crop).
 * boundingBox: ML Kit bounding box in pixels (relative to fullFrame).
 * leftEyePos & rightEyePos: optional ML Kit eye landmark positions for face alignment.
 */
data class FaceInstance(
    val videoUri: String,
    val timestampMs: Long,
    val boundingBox: RectF,
    val headEulerAngleX: Float,   // pitch
    val headEulerAngleY: Float,   // yaw
    val headEulerAngleZ: Float,   // roll
    val smilingProbability: Float,
    val leftEyeOpenProbability: Float,
    val rightEyeOpenProbability: Float,
    val sharpness: Float,
    val qualityScore: Float,
    val fullFrame: Bitmap,
    val leftEyePos: PointF? = null,
    val rightEyePos: PointF? = null,
    val embedding: FloatArray = FloatArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceInstance) return false
        return videoUri == other.videoUri && timestampMs == other.timestampMs &&
                boundingBox == other.boundingBox
    }

    override fun hashCode(): Int = 31 * videoUri.hashCode() + timestampMs.hashCode()
}

/**
 * A person identified by clustering across a single video.
 */
data class Person(
    val id: String,
    val videoUri: String,
    val faces: List<FaceInstance>,
    val centroid: FloatArray,
    val appearanceCount: Int = 0,
    val appearanceSegments: List<AppearanceSegment> = emptyList()
) {
    val representativeFace: FaceInstance?
        get() = faces.maxByOrNull { it.qualityScore }
}

/**
 * One contiguous appearance segment for a person in a video.
 */
data class AppearanceSegment(
    val startMs: Long,
    val endMs: Long
)

/**
 * Progress emitted during video processing.
 */
data class ProcessingProgress(
    val framesProcessed: Int,
    val totalFrames: Int,
    val facesDetected: Int
) {
    val fraction: Float
        get() = if (totalFrames > 0) framesProcessed.toFloat() / totalFrames else 0f
}
