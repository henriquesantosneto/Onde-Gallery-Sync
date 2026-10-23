package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper

/** Modelos de miniatura otimizados para a grade (MediaStore + tamanho fixo). */
object GalleryThumbnailModels {
    fun gridPixelSize(cellMinPx: Int): Int = (cellMinPx * 1.35f).toInt().coerceIn(128, 512)

    /**
     * Preferência: miniatura do sistema (rápida). Fallback: URI/arquivo para o Coil redimensionar.
     */
    fun resolveGridModel(
        context: Context,
        item: GalleryDisplayItem,
        sizePx: Int,
    ): Any? {
        item.localUri?.let { uri ->
            loadSystemThumbnail(context, uri, item.localMediaId, item.kind, sizePx)?.let { return it }
            if (item.kind == MediaKind.VIDEO) {
                VideoThumbnailHelper.captureFromContentUri(context, uri, strictFrames = false)
                    ?.let { return it }
                return uri
            }
            return uri
        }
        return item.thumbnailModel
    }

    private fun loadSystemThumbnail(
        context: Context,
        uri: Uri,
        localMediaId: Long?,
        kind: MediaKind,
        sizePx: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }?.let { bmp ->
                if (kind == MediaKind.VIDEO) return bmp
                if (VideoThumbnailHelper.isAcceptableFrame(bmp)) return bmp
                bmp.recycle()
            }
        }
        if (localMediaId != null) {
            @Suppress("DEPRECATION")
            when (kind) {
                MediaKind.IMAGE -> {
                    return runCatching {
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver,
                            localMediaId,
                            MediaStore.Images.Thumbnails.MINI_KIND,
                            null,
                        )
                    }.getOrNull()
                }
                MediaKind.VIDEO -> {
                    for (thumbKind in intArrayOf(
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        MediaStore.Video.Thumbnails.MICRO_KIND,
                    )) {
                        val bmp = runCatching {
                            MediaStore.Video.Thumbnails.getThumbnail(
                                context.contentResolver,
                                localMediaId,
                                thumbKind,
                                null,
                            )
                        }.getOrNull()
                        if (bmp != null && bmp.width > 0 && bmp.height > 0) return bmp
                    }
                    runCatching {
                        context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                    }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
                }
            }
        }
        return null
    }
}
