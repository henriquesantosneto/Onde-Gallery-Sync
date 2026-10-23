package com.onegallery.sync.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.graphics.scale
import java.io.File
import kotlin.math.max
import kotlin.math.min

object VideoThumbnailHelper {
    private const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 85
    private const val LUMA_SAMPLE_EDGE = 16
    /** Acima desta fração de pixels escuros, descarta o frame. */
    private const val MOSTLY_BLACK_RATIO = 0.90f
    private const val DARK_LUMA_THRESHOLD = 28.0

    private val FRAME_TIMES_US = longArrayOf(
        500_000L,
        1_000_000L,
        2_000_000L,
        3_000_000L,
        5_000_000L,
        8_000_000L,
        12_000_000L,
        20_000_000L,
        30_000_000L,
    )

    fun captureToFile(context: Context, uri: Uri, destination: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val bitmap = context.contentResolver.loadThumbnail(
                    uri,
                    Size(MAX_EDGE_PX, MAX_EDGE_PX),
                    null,
                )
                if (isAcceptableFrame(bitmap)) {
                    writeBitmap(destination, downscale(bitmap))
                    return true
                }
            }
        }
        captureFromContentUri(context, uri)?.let { bitmap ->
            writeBitmap(destination, downscale(bitmap))
            return true
        }
        return captureToFileWithRetriever(destination) { it.setDataSource(context, uri) }
    }

    fun captureToFile(source: File, destination: File): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        return captureToFileWithRetriever(destination) { it.setDataSource(source.absolutePath) }
    }

    /** Miniatura JPEG já gravada — rejeita frames quase pretos commitados por versões antigas. */
    fun isAcceptableThumbnailFile(file: File): Boolean {
        if (!file.isFile || file.length() < 128L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val sample = max(
            1,
            min(bounds.outWidth, bounds.outHeight) / LUMA_SAMPLE_EDGE,
        )
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return false
        return isAcceptableFrame(bitmap).also { bitmap.recycle() }
    }

    fun isAcceptableFrame(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val sample = bitmap.scale(LUMA_SAMPLE_EDGE, LUMA_SAMPLE_EDGE)
        return try {
            !isMostlyBlackSample(sample)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private inline fun captureToFileWithRetriever(
        destination: File,
        setSource: (MediaMetadataRetriever) -> Unit,
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            setSource(retriever)
            extractFrameBitmap(retriever)?.let { frame ->
                writeBitmap(destination, downscale(frame))
                true
            } ?: false
        }.getOrDefault(false).also {
            runCatching { retriever.release() }
        }
    }

    private fun extractFrameBitmap(retriever: MediaMetadataRetriever): Bitmap? {
        retriever.embeddedPicture?.takeIf { it.isNotEmpty() }?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { bmp ->
                bmp.width > 0 && bmp.height > 0 && isAcceptableFrame(bmp)
            }?.let { return it }
        }
        val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.times(1_000L)
            ?: 0L
        val times = buildFrameTimesUs(durationUs)
        for (timeUs in times) {
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null && frame.width > 0 && frame.height > 0 && isAcceptableFrame(frame)) {
                return frame
            }
            frame?.recycle()
        }
        return null
    }

    private fun buildFrameTimesUs(durationUs: Long): LongArray {
        if (durationUs <= 0L) return FRAME_TIMES_US
        val fromDuration = longArrayOf(
            durationUs / 20,
            durationUs / 10,
            durationUs / 6,
            durationUs / 4,
            durationUs / 2,
        ).filter { it in 1L until durationUs }
        return (FRAME_TIMES_US.toList() + fromDuration).distinct().sorted().toLongArray()
    }

    private fun isMostlyBlackSample(sample: Bitmap): Boolean {
        val w = sample.width
        val h = sample.height
        if (w <= 0 || h <= 0) return true
        var dark = 0
        val total = w * h
        var lumaSum = 0.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = sample.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                lumaSum += luma
                if (luma < DARK_LUMA_THRESHOLD) dark++
            }
        }
        val avg = lumaSum / total
        if (avg < DARK_LUMA_THRESHOLD) return true
        return dark.toFloat() / total.toFloat() >= MOSTLY_BLACK_RATIO
    }

    fun captureFromContentUri(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val thumb = context.contentResolver.loadThumbnail(uri, Size(MAX_EDGE_PX, MAX_EDGE_PX), null)
                if (isAcceptableFrame(thumb)) return thumb
                thumb.recycle()
            }
        }
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            extractFrameBitmap(retriever)?.let(::downscale)
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    private fun downscale(source: Bitmap): Bitmap {
        val max = maxOf(source.width, source.height)
        if (max <= MAX_EDGE_PX) return source
        val scale = MAX_EDGE_PX.toFloat() / max
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return source.scale(w, h)
    }

    private fun writeBitmap(destination: File, bitmap: Bitmap) {
        destination.parentFile?.mkdirs()
        destination.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }
}
