package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Modelos de miniatura otimizados para a grade (MediaStore + tamanho fixo). */
object GalleryThumbnailModels {
    private val devicePosterSlots = Semaphore(2)

    fun gridPixelSize(cellMinPx: Int): Int = (cellMinPx * 1.35f).toInt().coerceIn(128, 512)

    /**
     * Poster já existente no MediaStore. Não decodifica o vídeo no Coil
     * e não corre durante a rolagem (quem chama espera a grade parar).
     */
    suspend fun loadDeviceVideoPoster(
        context: Context,
        uri: Uri,
        mediaId: Long?,
        sizePx: Int,
    ): Bitmap? = devicePosterSlots.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                loadSystemThumbnail(
                    context,
                    uri,
                    mediaId,
                    MediaKind.VIDEO,
                    sizePx.coerceIn(64, 512),
                )?.toSoftwareBitmap()
            }.getOrNull()
        }
    }

    /**
     * Preferência: miniatura do sistema (rápida). Fallback: URI/arquivo para o Coil redimensionar.
     */
    /**
     * Faixa do detalhe: só Bitmap ou JPEG em cache — URI de vídeo quebra no Coil.
     */
    fun resolveFilmstripVideoModel(
        context: Context,
        item: GalleryDisplayItem,
        sizePx: Int,
    ): Any? {
        if (item.kind != MediaKind.VIDEO) return null
        val uri = item.localUri ?: return null
        loadSystemThumbnail(context, uri, item.localMediaId, item.kind, sizePx)?.let { return it }
        VideoThumbnailHelper.captureFromContentUri(context, uri, strictFrames = false)
            ?.let { return it }
        return null
    }

    /** MediaStore / MINI_KIND — rápido para pintar célula de vídeo na grade. */
    fun loadGridVideoBitmapFast(
        context: Context,
        item: GalleryDisplayItem,
        sizePx: Int,
    ): Bitmap? {
        if (item.kind != MediaKind.VIDEO) return null
        val uri = item.localUri ?: return null
        return loadSystemThumbnail(context, uri, item.localMediaId, MediaKind.VIDEO, sizePx)
    }

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

internal fun Bitmap.toSoftwareBitmap(): Bitmap? {
    if (config != Bitmap.Config.HARDWARE) return this
    val software = copy(Bitmap.Config.ARGB_8888, false)
    recycle()
    return software
}
