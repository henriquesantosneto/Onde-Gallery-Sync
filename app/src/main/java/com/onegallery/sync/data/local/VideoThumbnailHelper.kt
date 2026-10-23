package com.onegallery.sync.data.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.graphics.scale
import java.io.File

object VideoThumbnailHelper {
    private const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 85
    /** Vários instantes — frame 0 costuma falhar (preto / codec). */
    private val FRAME_TIMES_US = longArrayOf(0L, 1_000_000L, 3_000_000L, 8_000_000L)

    fun captureToFile(context: Context, uri: Uri, destination: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val bitmap = context.contentResolver.loadThumbnail(
                    uri,
                    Size(MAX_EDGE_PX, MAX_EDGE_PX),
                    null,
                )
                writeBitmap(destination, downscale(bitmap))
                return true
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
        for (timeUs in FRAME_TIMES_US) {
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null && frame.width > 0 && frame.height > 0) {
                return frame
            }
        }
        return null
    }

    fun captureFromContentUri(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return context.contentResolver.loadThumbnail(uri, Size(MAX_EDGE_PX, MAX_EDGE_PX), null)
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
