package com.onegallery.sync.ui.places

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Decodifica JPEG/PNG aplicando rotação EXIF (miniaturas do mapa e marcadores). */
object ExifOrientedBitmap {
    fun decodeFile(path: String, maxEdgePx: Int = 256): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = computeInSampleSize(
            options.outWidth,
            options.outHeight,
            maxEdgePx,
        )
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        return applyExifOrientation(decoded, path)
    }

    private fun computeInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / sample > maxEdgePx * 2) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun applyExifOrientation(bitmap: Bitmap, path: String): Bitmap {
        val rotation = exifRotationDegrees(path) ?: return bitmap
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun exifRotationDegrees(path: String): Int? {
        return runCatching {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        }.getOrNull()
    }
}
