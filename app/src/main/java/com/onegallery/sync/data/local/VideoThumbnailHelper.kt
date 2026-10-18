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

    fun captureToFile(context: Context, uri: Uri, destination: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            writeBitmap(destination, downscale(frame))
            true
        }.getOrDefault(false).also {
            runCatching { retriever.release() }
        }
    }

    fun captureToFile(source: File, destination: File): Boolean {
        if (!source.isFile) return false
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(source.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            writeBitmap(destination, downscale(frame))
            true
        }.getOrDefault(false).also {
            runCatching { retriever.release() }
        }
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
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::downscale)
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
