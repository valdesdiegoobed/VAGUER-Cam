package com.hazbu.xcam.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlin.math.max
import kotlin.math.min

object ImageProcessor {

    fun decodeOriented(path: String, maxSide: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide * 2) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null

        val exif = try {
            ExifInterface(path)
        } catch (_: Throwable) {
            null
        }
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }

        if (matrix.isIdentity) return decoded
        return try {
            val corrected = Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                matrix,
                true,
            )
            if (corrected !== decoded) decoded.recycle()
            corrected
        } catch (_: Throwable) {
            decoded
        }
    }

    /**
     * Fast five-tap sharpening used for still images before they are converted
     * to the looping MP4 that the original xCam engine expects.
     */
    fun sharpen(source: Bitmap, amount: Float): Bitmap {
        val strength = amount.coerceIn(0f, 1f)
        if (strength < 0.01f || source.width < 3 || source.height < 3) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        input.copyInto(output)

        val edge = strength * 0.75f
        val center = 1f + edge * 4f

        fun clamp(v: Float): Int = min(255, max(0, v.toInt()))

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val c = input[i]
                val l = input[i - 1]
                val r = input[i + 1]
                val u = input[i - width]
                val d = input[i + width]

                val a = c ushr 24 and 0xff
                val cr = c ushr 16 and 0xff
                val cg = c ushr 8 and 0xff
                val cb = c and 0xff

                val rr = cr * center - edge * (
                    (l ushr 16 and 0xff) + (r ushr 16 and 0xff) +
                        (u ushr 16 and 0xff) + (d ushr 16 and 0xff)
                    )
                val gg = cg * center - edge * (
                    (l ushr 8 and 0xff) + (r ushr 8 and 0xff) +
                        (u ushr 8 and 0xff) + (d ushr 8 and 0xff)
                    )
                val bb = cb * center - edge * (
                    (l and 0xff) + (r and 0xff) + (u and 0xff) + (d and 0xff)
                    )

                output[i] = (a shl 24) or (clamp(rr) shl 16) or
                    (clamp(gg) shl 8) or clamp(bb)
            }
        }

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }
}
