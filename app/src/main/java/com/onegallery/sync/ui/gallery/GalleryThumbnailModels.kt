package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Modelos de miniatura otimizados para a grade (MediaStore + tamanho fixo). */
object GalleryThumbnailModels {
    private val devicePosterSlots = Semaphore(2)

    fun gridPixelSize(cellMinPx: Int): Int = cellMinPx.coerceIn(96, 384)

    /**
     * Poster já existente no MediaStore. Não decodifica o vídeo no Coil
     * e não corre durante a rolagem (quem chama espera a grade parar).
     */
    suspend fun loadDeviceImagePoster(
        context: Context,
        uri: Uri,
        mediaId: Long?,
        sizePx: Int,
    ): Bitmap? = devicePosterSlots.withPermit {
        withContext(Dispatchers.IO) {
            val edge = sizePx.coerceIn(64, 512)
            runCatching { decodeSampledContentBitmap(context, uri, edge) }.getOrNull()
                ?: runCatching {
                    loadSystemThumbnail(context, uri, mediaId, MediaKind.IMAGE, edge)
                }.getOrNull()
        }
    }

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
                )?.ownedSoftwareBitmap()
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
            }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }?.let { raw ->
                val bmp = raw.ownedSoftwareBitmap() ?: return@let
                if (bmp.width > 0 && bmp.height > 0) return bmp
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

    /**
     * Capa de álbum: JPEG em disco (mesmo com nome de vídeo) ou foto/vídeo no aparelho.
     * Não passa pelo Coil, que deixa a capa vazia quando o arquivo não é uma imagem óbvia.
     */
    suspend fun decodeAlbumCoverBitmap(context: Context, source: Any, sizePx: Int = 480): Bitmap? {
        val edge = sizePx.coerceIn(160, 512)
        return when (source) {
            is Bitmap -> source.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            is DiskThumb -> decodeSampledCoverFile(source.file, edge)
            is File -> decodeSampledCoverFile(source, edge)
            is DeviceVideoCover -> loadDeviceVideoPoster(context, source.uri, source.mediaId, edge)
            is Uri -> if (source.isVideoContentUri()) {
                loadDeviceVideoPoster(context, source, null, edge)
            } else {
                loadDeviceImagePoster(context, source, null, edge)
            }
            else -> null
        }
    }

    private fun decodeSampledCoverFile(file: File, edge: Int): Bitmap? {
        if (!file.isFile || file.length() !in 128L..48_000_000L) return null
        val ext = file.extension.lowercase()
        val imageExt = ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" || ext == "gif"
        if (!imageExt && !file.isSafeGridImageFile()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleCoverSize(bounds.outWidth, bounds.outHeight, edge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            if (decoded.width <= 0 || decoded.height <= 0) {
                decoded.recycle()
                return null
            }
            decoded.ownedSoftwareBitmap()
        }.getOrNull()
    }

    private fun sampleCoverSize(width: Int, height: Int, target: Int): Int {
        val longest = maxOf(width, height).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= target && sample < 32) sample *= 2
        return sample
    }

    /** JPEG da foto no aparelho, já reduzido. Não depende do poster do MediaStore. */
    private fun decodeSampledContentBitmap(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxEdge / sample > sizePx * 2 && sample < 32) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
        } ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) {
            decoded.recycle()
            return null
        }
        return decoded.ownedSoftwareBitmap()
    }
}

internal fun Bitmap.toSoftwareBitmap(): Bitmap? {
    if (isRecycled) return null
    if (config != Bitmap.Config.HARDWARE) return this
    return copy(Bitmap.Config.ARGB_8888, false) ?: this
}

/**
 * Bitmap que só este código criou (MediaStore). A cópia de hardware pode
 * substituir o original; o original do Coil não passa por aqui.
 */
internal fun Bitmap.ownedSoftwareBitmap(): Bitmap? {
    val software = toSoftwareBitmap() ?: return null
    if (software !== this) recycle()
    return software
}

/** Cópia independente. O bitmap de origem (Coil ou cache) continua válido. */
internal fun Bitmap.copyOwnedSoftwareBitmap(): Bitmap? {
    if (isRecycled || width <= 0 || height <= 0) return null
    val target = when (config) {
        null, Bitmap.Config.HARDWARE -> Bitmap.Config.ARGB_8888
        else -> config
    }
    return copy(target, false)
}
