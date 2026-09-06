package com.example.ensemble.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.ensemble.domain.Person
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

private const val TAG = "CollageGenerator"

object CollageGenerator {

    /**
     * Render a clean, high-resolution Bitmap collage of all identified [people].
     * Uses warm orange & skin-tone background palette.
     */
    fun generateCollageBitmap(people: List<Person>): Bitmap {
        val width = 1200
        val headerHeight = 160
        val footerHeight = 80
        val cardHeight = 220
        val spacing = 24
        val padding = 32

        val numPeople = people.size
        val totalContentHeight = if (numPeople == 0) 200 else numPeople * (cardHeight + spacing) - spacing
        val totalHeight = headerHeight + totalContentHeight + (padding * 2) + footerHeight

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background gradient: #1C120C to #2D1E16 (Warm Deep Mocha / Terracotta)
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, totalHeight.toFloat(),
                Color.parseColor("#1C120C"), Color.parseColor("#2D1E16"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), totalHeight.toFloat(), bgPaint)

        // Header Banner
        val headerPaint = Paint().apply {
            color = Color.parseColor("#261A13")
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight.toFloat(), headerPaint)

        val titlePaint = Paint().apply {
            color = Color.parseColor("#FFE0B2")
            textSize = 56f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("ENSEMBLE", padding.toFloat(), 70f, titlePaint)

        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#B39282")
            textSize = 28f
            isAntiAlias = true
        }
        canvas.drawText(
            "Identified $numPeople Person${if (numPeople != 1) "s" else ""} • Key Moments & Appearances",
            padding.toFloat(), 120f, subtitlePaint
        )

        // Draw Divider Line
        val linePaint = Paint().apply {
            color = Color.parseColor("#4D3326")
            strokeWidth = 3f
        }
        canvas.drawLine(0f, headerHeight.toFloat(), width.toFloat(), headerHeight.toFloat(), linePaint)

        // Render Cards for each Person
        var currentY = headerHeight + padding

        val cardBgPaint = Paint().apply {
            color = Color.parseColor("#261A13")
            isAntiAlias = true
        }

        val cardBorderPaint = Paint().apply {
            color = Color.parseColor("#4D3326")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        val personTitlePaint = Paint().apply {
            color = Color.parseColor("#FFFFFF")
            textSize = 34f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val statsPaint = Paint().apply {
            color = Color.parseColor("#FFAB40")
            textSize = 26f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val segmentTextPaint = Paint().apply {
            color = Color.parseColor("#FFE0B2")
            textSize = 22f
            isAntiAlias = true
        }

        val avatarBorderPaint = Paint().apply {
            color = Color.parseColor("#FF6D00")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        for (person in people) {
            val cardRect = RectF(
                padding.toFloat(),
                currentY.toFloat(),
                (width - padding).toFloat(),
                (currentY + cardHeight).toFloat()
            )

            // Draw Card background & border
            canvas.drawRoundRect(cardRect, 20f, 20f, cardBgPaint)
            canvas.drawRoundRect(cardRect, 20f, 20f, cardBorderPaint)

            // Render Crop Face Avatar (with generous margin showing face + shoulders/context)
            val repFace = person.representativeFace
            val avatarSize = 160
            val avatarLeft = padding + 30
            val avatarTop = currentY + 30

            if (repFace != null) {
                val croppedFace = cropFaceBitmap(repFace.fullFrame, repFace.boundingBox)
                val avatarBitmap = getCircularBitmap(croppedFace, avatarSize)

                canvas.drawBitmap(avatarBitmap, avatarLeft.toFloat(), avatarTop.toFloat(), null)
                canvas.drawCircle(
                    avatarLeft + avatarSize / 2f,
                    avatarTop + avatarSize / 2f,
                    avatarSize / 2f,
                    avatarBorderPaint
                )
            }

            // Person Info Text
            val textLeft = (avatarLeft + avatarSize + 30).toFloat()

            canvas.drawText(person.id, textLeft, (currentY + 65).toFloat(), personTitlePaint)

            val statsText = "${person.faces.size} Face Shot${if (person.faces.size != 1) "s" else ""} • " +
                    "${person.appearanceCount} Appearance Segment${if (person.appearanceCount != 1) "s" else ""}"
            canvas.drawText(statsText, textLeft, (currentY + 105).toFloat(), statsPaint)

            // Format Appearance Segments
            val segmentFormatted = person.appearanceSegments.joinToString(separator = ", ") { seg ->
                "${formatMs(seg.startMs)} – ${formatMs(seg.endMs)}"
            }
            val displaySegments = if (segmentFormatted.length > 55) {
                segmentFormatted.take(52) + "..."
            } else {
                segmentFormatted
            }

            canvas.drawText(
                "Timestamps: $displaySegments",
                textLeft,
                (currentY + 155).toFloat(),
                segmentTextPaint
            )

            currentY += cardHeight + spacing
        }

        // Footer Banner
        val footerY = totalHeight - footerHeight
        val footerPaint = Paint().apply {
            color = Color.parseColor("#B39282")
            textSize = 22f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Generated by Ensemble App • High Performance On-Device Video Face Clustering",
            width / 2f,
            (footerY + 45).toFloat(),
            footerPaint
        )

        return bitmap
    }

    /**
     * Save generated collage bitmap to cache file and return FileProvider Uri.
     */
    fun saveCollageToCache(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val cacheDir = File(context.cacheDir, "collages").apply { mkdirs() }
            val file = File(cacheDir, "ensemble_collage.jpg")
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.flush()
            outputStream.close()

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            Log.i(TAG, "Saved collage to ${file.absolutePath}, uri: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save collage to cache: ${e.message}", e)
            null
        }
    }

    /**
     * Generous face crop: expands bounding box by 70% on each side to show face + shoulders/context.
     */
    private fun cropFaceBitmap(fullFrame: Bitmap, boundingBox: RectF): Bitmap {
        val marginX = boundingBox.width() * 0.70f
        val marginY = boundingBox.height() * 0.70f

        val left = max(0f, boundingBox.left - marginX).toInt()
        val top = max(0f, boundingBox.top - marginY).toInt()
        val right = min(fullFrame.width.toFloat(), boundingBox.right + marginX).toInt()
        val bottom = min(fullFrame.height.toFloat(), boundingBox.bottom + marginY).toInt()

        val w = max(1, right - left)
        val h = max(1, bottom - top)

        return Bitmap.createBitmap(fullFrame, left, top, w, h)
    }

    private fun getCircularBitmap(src: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
        }

        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawRoundRect(rectF, size / 2f, size / 2f, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val scaledSrc = Bitmap.createScaledBitmap(src, size, size, true)
        canvas.drawBitmap(scaledSrc, rect, rect, paint)

        return output
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }
}
