package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.onegallery.sync.data.local.MediaKind

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
            return runCatching {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            }.getOrNull()
        }
        if (localMediaId != null) {
            @Suppress("DEPRECATION")
            return when (kind) {
                MediaKind.IMAGE -> runCatching {
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver,
                        localMediaId,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null,
                    )
                }.getOrNull()
                MediaKind.VIDEO -> runCatching {
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        localMediaId,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null,
                    )
                }.getOrNull()
            }
        }
        return null
    }
}
