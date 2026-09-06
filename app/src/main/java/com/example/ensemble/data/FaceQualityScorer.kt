package com.example.ensemble.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure computation: no Android framework state, fully unit-testable.
 * All functions are stateless.
 */
object FaceQualityScorer {

    // Quality score weights (must sum ≈ 1.0)
    private const val W_FRONTALITY = 0.35f
    private const val W_SHARPNESS  = 0.30f
    private const val W_EYES_OPEN  = 0.20f
    private const val W_SMILING    = 0.15f

    // Sharpness normalization cap — Laplacian variance above this is treated as max sharpness
    private const val SHARPNESS_NORM_CAP = 500f

    // Minimum face region dimension to bother computing sharpness (pixels)
    private const val MIN_FACE_DIM = 10

    /**
     * Compute variance of Laplacian on the face region within [fullFrame].
     * Returns a raw variance (higher = sharper). Clamped face box is used.
     */
    fun computeSharpness(fullFrame: Bitmap, boundingBox: RectF): Float {
        val frameW = fullFrame.width
        val frameH = fullFrame.height

        // Generous crop: expand box by 20% to include hairline etc.
        val expand = 0.2f
        val bw = boundingBox.width()
        val bh = boundingBox.height()
        val left   = (boundingBox.left   - bw * expand).toInt().coerceIn(0, frameW - 1)
        val top    = (boundingBox.top    - bh * expand).toInt().coerceIn(0, frameH - 1)
        val right  = (boundingBox.right  + bw * expand).toInt().coerceIn(0, frameW)
        val bottom = (boundingBox.bottom + bh * expand).toInt().coerceIn(0, frameH)

        val cropW = right - left
        val cropH = bottom - top
        if (cropW < MIN_FACE_DIM || cropH < MIN_FACE_DIM) return 0f

        // Compute Laplacian variance on grayscale crop
        // Laplacian 3×3 kernel: [0,1,0 / 1,-4,1 / 0,1,0]
        var sum   = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until cropH - 1) {
            for (x in 1 until cropW - 1) {
                val py = top  + y
                val px = left + x
                val gray    = grayAt(fullFrame, px,   py)
                val grayN   = grayAt(fullFrame, px,   py - 1)
                val grayS   = grayAt(fullFrame, px,   py + 1)
                val grayW   = grayAt(fullFrame, px-1, py)
                val grayE   = grayAt(fullFrame, px+1, py)
                val lap = (grayN + grayS + grayW + grayE - 4.0 * gray)
                sum   += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return variance.toFloat().coerceAtLeast(0f)
    }

    private fun grayAt(bmp: Bitmap, x: Int, y: Int): Double {
        val c = bmp.getPixel(x, y)
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c))
    }

    /**
     * Normalize raw sharpness to [0,1] using the cap constant.
     */
    fun normalizeSharpness(raw: Float): Float =
        (raw / SHARPNESS_NORM_CAP).coerceIn(0f, 1f)

    /**
     * Compute frontality score in [0,1] from Euler yaw + pitch.
     * Both angles are in degrees. Perfect frontal = score 1.0.
     */
    fun frontality(yaw: Float, pitch: Float): Float {
        val normalizedYaw   = (abs(yaw)   / 45f).coerceIn(0f, 1f)
        val normalizedPitch = (abs(pitch) / 30f).coerceIn(0f, 1f)
        return (1f - (normalizedYaw + normalizedPitch) / 2f).coerceIn(0f, 1f)
    }

    /**
     * Compute the composite representative-shot score.
     */
    fun qualityScore(
        yaw: Float,
        pitch: Float,
        sharpnessRaw: Float,
        leftEyeOpen: Float,
        rightEyeOpen: Float,
        smiling: Float
    ): Float {
        val frontalityScore   = frontality(yaw, pitch)
        val sharpnessNorm     = normalizeSharpness(sharpnessRaw)
        val eyesOpenAvg       = (leftEyeOpen + rightEyeOpen) / 2f
        return (W_FRONTALITY * frontalityScore +
                W_SHARPNESS  * sharpnessNorm  +
                W_EYES_OPEN  * eyesOpenAvg    +
                W_SMILING    * smiling).coerceIn(0f, 1f)
    }
}
