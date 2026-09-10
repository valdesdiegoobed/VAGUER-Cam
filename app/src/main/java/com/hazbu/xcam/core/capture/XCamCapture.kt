package com.hazbu.xcam.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.hazbu.xcam.data.Constants
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object XCamCapture {

    fun createJpeg(
        context: Context,
        path: String,
        targetW: Int,
        targetH: Int,
        rotation: Int,
        mirrored: Boolean,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        fitMode: String = Constants.FIT_MODE_FIT,
        brightness: Float = 0f,
        contrast: Float = 0f,
        saturation: Float = 0f,
        timeMs: Int = 1000,
        printLog: (String) -> Unit,
    ): ByteArray? {
        printLog(
            "Capture Process: Starting for $path (Time: $timeMs ms, rot=$rotation, " +
                "scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)}, " +
                "offset=${"%.2f".format(offsetX)},${"%.2f".format(offsetY)}, fit=$fitMode)",
        )

        return try {
            val rawBitmap: Bitmap? =
                if (path.lowercase().endsWith(".mp4")) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, path.toUri())

                        val durationMs =
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                        val loopedTimeMs =
                            if (durationMs > 0L) {
                                timeMs.toLong() % durationMs
                            } else {
                                timeMs.toLong()
                            }
                        val targetUs =
                            if (loopedTimeMs > 100L) {
                                (loopedTimeMs - 100L) * 1000L
                            } else {
                                loopedTimeMs * 1000L
                            }

                        printLog(
                            "Capture Process: Extracting frame at $targetUs us (CLOSEST)",
                        )

                        retriever.getFrameAtTime(
                            targetUs,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                        ) ?: retriever.getFrameAtTime(
                            timeMs * 1000L,
                            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                        )
                    } finally {
                        retriever.release()
                    }
                } else {
                    printLog("Capture Process: Decoding image from $path")
                    context.contentResolver.openInputStream(path.toUri())?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }

            if (rawBitmap == null) {
                printLog("Capture Process: Raw bitmap is null")
                return null
            }

            printLog(
                "Capture Process: Source Size ${rawBitmap.width}x${rawBitmap.height}",
            )

            // The editor and the Media3 preview both work on a square canvas.
            // Build the still/YUV replacement on that same geometry first so
            // portrait/landscape, zoom and movement are identical everywhere.
            val workingW = Constants.DEFAULT_CAPTURE_WIDTH
            val workingH = Constants.DEFAULT_CAPTURE_HEIGHT
            val workingBitmap =
                Bitmap.createBitmap(workingW, workingH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(workingBitmap)
            canvas.drawColor(Color.BLACK)

            val sourceW = rawBitmap.width.toFloat().coerceAtLeast(1f)
            val sourceH = rawBitmap.height.toFloat().coerceAtLeast(1f)

            val baseRect =
                when (fitMode) {
                    Constants.FIT_MODE_STRETCH ->
                        RectF(0f, 0f, workingW.toFloat(), workingH.toFloat())

                    Constants.FIT_MODE_FILL -> {
                        val baseScale =
                            max(workingW / sourceW, workingH / sourceH)
                        val drawW = sourceW * baseScale
                        val drawH = sourceH * baseScale
                        RectF(
                            (workingW - drawW) / 2f,
                            (workingH - drawH) / 2f,
                            (workingW + drawW) / 2f,
                            (workingH + drawH) / 2f,
                        )
                    }

                    else -> {
                        val baseScale =
                            min(workingW / sourceW, workingH / sourceH)
                        val drawW = sourceW * baseScale
                        val drawH = sourceH * baseScale
                        RectF(
                            (workingW - drawW) / 2f,
                            (workingH - drawH) / 2f,
                            (workingW + drawW) / 2f,
                            (workingH + drawH) / 2f,
                        )
                    }
                }

            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter =
                        buildColorFilter(
                            brightness = brightness,
                            contrast = contrast,
                            saturation = saturation,
                        )
                }

            val safeScaleX = scaleX.coerceIn(0.25f, 4f)
            val safeScaleY = scaleY.coerceIn(0.25f, 4f)
            val safeOffsetX = offsetX.coerceIn(-2f, 2f)
            val safeOffsetY = offsetY.coerceIn(-2f, 2f)
            val safeRotation = ((rotation % 360) + 360) % 360

            val centerX = workingW / 2f
            val centerY = workingH / 2f

            canvas.save()
            // Match Android View transforms used by TransformableImageView:
            // translation is in screen coordinates, then rotation/scale happen
            // around the center of the square preview.
            canvas.translate(
                safeOffsetX * workingW / 2f,
                safeOffsetY * workingH / 2f,
            )
            canvas.rotate(safeRotation.toFloat(), centerX, centerY)

            if (mirrored || abs(safeScaleX - 1f) > 0.001f || abs(safeScaleY - 1f) > 0.001f) {
                canvas.scale(
                    safeScaleX * if (mirrored) -1f else 1f,
                    safeScaleY,
                    centerX,
                    centerY,
                )
            }

            canvas.drawBitmap(rawBitmap, null, baseRect, paint)
            canvas.restore()

            val safeTargetW = targetW.coerceAtLeast(1)
            val safeTargetH = targetH.coerceAtLeast(1)
            val finalBitmap =
                if (safeTargetW == workingW && safeTargetH == workingH) {
                    workingBitmap
                } else {
                    Bitmap.createScaledBitmap(
                        workingBitmap,
                        safeTargetW,
                        safeTargetH,
                        true,
                    )
                }

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            val result = out.toByteArray()

            printLog(
                "Capture Process: SUCCESS. Final Size ${finalBitmap.width}x${finalBitmap.height} " +
                    "(${result.size} bytes)",
            )

            if (!rawBitmap.isRecycled) rawBitmap.recycle()
            if (finalBitmap !== workingBitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            if (!finalBitmap.isRecycled) finalBitmap.recycle()

            result
        } catch (e: Exception) {
            printLog("createCaptureJpeg error: ${e.message}")
            null
        }
    }

    private fun buildColorFilter(
        brightness: Float,
        contrast: Float,
        saturation: Float,
    ): ColorMatrixColorFilter? {
        val safeBrightness = brightness.coerceIn(-1f, 1f)
        val safeContrast = contrast.coerceIn(-1f, 1f)
        val safeSaturation = saturation.coerceIn(-100f, 100f)

        if (
            abs(safeBrightness) < 0.001f &&
            abs(safeContrast) < 0.001f &&
            abs(safeSaturation) < 0.001f
        ) {
            return null
        }

        val saturationFactor = (1f + safeSaturation / 100f).coerceAtLeast(0f)
        val color = ColorMatrix().apply { setSaturation(saturationFactor) }

        val contrastFactor =
            if (safeContrast >= 0f) {
                1f + safeContrast * 1.5f
            } else {
                1f + safeContrast
            }
        val translate =
            128f * (1f - contrastFactor) + safeBrightness * 255f

        val adjustment =
            ColorMatrix(
                floatArrayOf(
                    contrastFactor, 0f, 0f, 0f, translate,
                    0f, contrastFactor, 0f, 0f, translate,
                    0f, 0f, contrastFactor, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )

        color.postConcat(adjustment)
        return ColorMatrixColorFilter(color)
    }
}
