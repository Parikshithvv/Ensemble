package com.example.ensemble.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.ensemble.domain.DataError
import com.example.ensemble.domain.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min

private const val TAG = "VideoFrameExtractor"

// Sample interval in microseconds (~175ms)
private const val SAMPLE_INTERVAL_US = 175_000L

// Max dimension (width or height) to cap bitmaps to save RAM and prevent GC stalls
private const val MAX_BITMAP_DIMENSION = 1280

data class FrameResult(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val frameIndex: Int,
    val totalFrames: Int
)

class VideoFrameExtractor(private val context: Context) {

    /**
     * Emits sampled frames from [uri] sequentially with strict backpressure.
     * Each frame is decoded using OPTION_CLOSEST for exact timestamp accuracy
     * and copied into an independent, un-shared ARGB_8888 software Bitmap.
     * All emit calls occur directly on the Flow collector context (no context invariant violations).
     */
    fun extractFrames(
        uri: Uri,
        sampleIntervalUs: Long = SAMPLE_INTERVAL_US
    ): Flow<Result<FrameResult, DataError.Local>> = flow {
        // Read video metadata on Dispatchers.IO
        val (retriever, rotation, durationUs, totalFrames) = withContext(Dispatchers.IO) {
            val ret = MediaMetadataRetriever()
            try {
                ret.setDataSource(context, uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open video: ${e.message}", e)
                return@withContext Tuple4<MediaMetadataRetriever?, Int, Long, Int>(null, 0, 0L, 0)
            }

            val rawRotationString = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rawWidthString    = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val rawHeightString   = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rawDurationString = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

            Log.d(TAG, "RAW METADATA: ROTATION_STR='$rawRotationString', WIDTH_STR='$rawWidthString', HEIGHT_STR='$rawHeightString', DURATION_STR='$rawDurationString'")

            var rot = rawRotationString?.toIntOrNull() ?: 0

            if (rot == 0) {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.Video.Media.ORIENTATION),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val orientIndex = cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION)
                            if (orientIndex != -1) {
                                val mediaStoreOrient = cursor.getInt(orientIndex)
                                Log.d(TAG, "MediaStore ORIENTATION query returned: $mediaStoreOrient°")
                                if (mediaStoreOrient != 0) rot = mediaStoreOrient
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore orientation query failed: ${e.message}")
                }
            }

            if (rot == 0) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val exifOrientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        val exifDegrees = when (exifOrientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                        if (exifDegrees != 0) {
                            rot = exifDegrees
                            Log.d(TAG, "ExifInterface TAG_ORIENTATION returned: $exifDegrees°")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ExifInterface orientation check failed: ${e.message}")
                }
            }

            val durUs = rawDurationString?.toLongOrNull()?.times(1000L) ?: 0L
            val totFrames = ((durUs / sampleIntervalUs) + 1).toInt().coerceAtLeast(1)

            Log.d(TAG, "FINAL DERIVED METADATA: duration=${durUs / 1000}ms, effectiveRotation=$rot°, totalFrames=$totFrames")
            Tuple4(ret, rot, durUs, totFrames)
        }

        if (retriever == null || durationUs <= 0L) {
            emit(Result.Error(DataError.Local.UNSUPPORTED_VIDEO))
            return@flow
        }

        var activeRetriever: MediaMetadataRetriever = retriever
        var frameIndex = 0
        var currentUs = 0L

        while (currentUs <= durationUs && coroutineContext.isActive) {
            val frameResult: FrameResult? = withContext(Dispatchers.IO) {
                try {
                    // Recreate retriever every 60 frames to prevent C++ decoder memory accumulation
                    if (frameIndex > 0 && frameIndex % 60 == 0) {
                        try {
                            activeRetriever.release()
                        } catch (ignored: Exception) {}
                        val newRet = MediaMetadataRetriever()
                        newRet.setDataSource(context, uri)
                        activeRetriever = newRet
                    }

                    val rawBitmap = activeRetriever.getFrameAtTime(
                        currentUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: activeRetriever.getFrameAtTime(
                        currentUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )

                    if (rawBitmap != null) {
                        if (frameIndex == 0) {
                            Log.d(TAG, "RAW DECODED BITMAP #0 dimensions: ${rawBitmap.width}x${rawBitmap.height} (width > height? ${rawBitmap.width > rawBitmap.height})")
                        }

                        val needsRotation = if (rotation != 0) {
                            if (rawBitmap.height > rawBitmap.width && (rotation == 90 || rotation == 270)) {
                                false
                            } else {
                                true
                            }
                        } else {
                            false
                        }

                        val rotatedBitmap = if (needsRotation) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            val rot = Bitmap.createBitmap(
                                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                            )
                            if (rot != rawBitmap) {
                                rawBitmap.recycle()
                            }
                            rot
                        } else {
                            rawBitmap
                        }

                        val scaledBitmap = if (rotatedBitmap.width > MAX_BITMAP_DIMENSION || rotatedBitmap.height > MAX_BITMAP_DIMENSION) {
                            val scale = min(
                                MAX_BITMAP_DIMENSION.toFloat() / rotatedBitmap.width,
                                MAX_BITMAP_DIMENSION.toFloat() / rotatedBitmap.height
                            )
                            val scaledW = (rotatedBitmap.width * scale).toInt().coerceAtLeast(1)
                            val scaledH = (rotatedBitmap.height * scale).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(rotatedBitmap, scaledW, scaledH, true)
                            if (scaled != rotatedBitmap) {
                                rotatedBitmap.recycle()
                            }
                            scaled
                        } else {
                            rotatedBitmap
                        }

                        // Fresh independent ARGB_8888 software Bitmap copy
                        val independentBitmap = scaledBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: scaledBitmap
                        if (independentBitmap != scaledBitmap) {
                            scaledBitmap.recycle()
                        }

                        if (frameIndex == 0) {
                            try {
                                val debugFile = File(context.filesDir, "debug_frame_0.jpg")
                                FileOutputStream(debugFile).use { out ->
                                    independentBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                                Log.d(TAG, "=== DEBUG FRAME 0 SAVED TO INTERNAL STORAGE ===")
                                Log.d(TAG, "EXACT FILE PATH: ${debugFile.absolutePath}")
                                Log.d(TAG, "================================================")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed saving debug frame 0", e)
                            }
                        }

                        FrameResult(
                            bitmap = independentBitmap,
                            timestampMs = currentUs / 1000L,
                            frameIndex = frameIndex,
                            totalFrames = totalFrames
                        )
                    } else null
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM at frame $frameIndex", e)
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Frame decode error at ${currentUs / 1000}ms: ${e.message}", e)
                    null
                }
            }

            if (frameResult != null) {
                Log.d(TAG, "Emitting Frame ${frameResult.frameIndex} @ ${frameResult.timestampMs}ms — ${frameResult.bitmap.width}x${frameResult.bitmap.height}")
                // EMIT DIRECTLY ON THE FLOW BUILDER CONTEXT (no Flow invariant violation!)
                emit(Result.Success(frameResult))
            }

            frameIndex++
            currentUs += sampleIntervalUs
        }

        withContext(Dispatchers.IO) {
            try {
                activeRetriever.release()
            } catch (ignored: Exception) {}
        }
        Log.d(TAG, "Extraction complete. $frameIndex frames processed.")
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
