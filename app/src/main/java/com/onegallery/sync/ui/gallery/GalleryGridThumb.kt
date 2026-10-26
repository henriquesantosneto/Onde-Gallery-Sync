package com.onegallery.sync.ui.gallery

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.imageLoader
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaCacheRepository

/**
 * Grade: só o JPEG já gravado, ou a foto que está no aparelho.
 * Não consulta disco nem MediaStore ao compor a célula.
 * A miniatura nova entra quando a lista troca, ou quando a rolagem para.
 */
@Composable
fun GalleryGridThumb(
    item: GalleryDisplayItem,
    contentDescription: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCache = remember(app) { app.mediaCacheRepository }
    val thumbPath = item.cachedPlaceholderThumb?.absolutePath
    val localKey = item.localUri?.toString()
    val paintModel = remember(item.canonicalPath, item.oneDriveItemId, thumbPath, localKey) {
        gridPaintModel(item, mediaCache)
    } ?: return
    val cacheKey = remember(item.canonicalPath, sizePx, thumbPath, localKey) {
        gridThumbCacheKey(item, sizePx)
    }
    val request = remember(paintModel, sizePx, cacheKey) {
        galleryGridImageRequest(
            context = context,
            data = paintModel,
            sizePx = sizePx,
            cacheKey = cacheKey,
        )
    }
    val placeholder = rememberPlaceholder(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholder,
    )
}

@Composable
private fun rememberPlaceholder(color: Color): ColorPainter {
    return remember(color) { ColorPainter(color) }
}

internal fun gridThumbCacheKey(item: GalleryDisplayItem, sizePx: Int): String {
    val thumb = item.cachedPlaceholderThumb?.absolutePath
    val local = item.localUri?.toString()
    return "${item.canonicalPath}:grid:$sizePx:$thumb:$local"
}

/** Caminho já conhecido. O Coil confere o arquivo fora da thread principal. */
internal fun gridPaintModel(
    item: GalleryDisplayItem,
    mediaCache: MediaCacheRepository,
): Any? {
    item.cachedPlaceholderThumb?.let { return DiskThumb(it) }
    if (item.isLocalPhotoOnDevice()) return item.localUri
    val remoteId = item.oneDriveItemId
    val file = if (!remoteId.isNullOrBlank()) {
        mediaCache.thumbFileByRemoteItemId(remoteId)
    } else {
        mediaCache.thumbFile(item.canonicalPath)
    }
    return DiskThumb(file)
}

/** Esquenta a memória com as células vizinhas, só com a lista parada. */
internal fun prefetchGalleryGridThumbs(
    context: Context,
    mediaCache: MediaCacheRepository,
    entries: List<GalleryGridEntry>,
    firstVisible: Int,
    lastVisible: Int,
    sizePx: Int,
    ahead: Int = 12,
) {
    val loader = context.imageLoader
    fun enqueueAt(index: Int) {
        val item = (entries.getOrNull(index) as? GalleryGridEntry.Photo)?.item ?: return
        val model = gridPaintModel(item, mediaCache) ?: return
        loader.enqueue(
            galleryGridImageRequest(
                context = context,
                data = model,
                sizePx = sizePx,
                cacheKey = gridThumbCacheKey(item, sizePx),
            ),
        )
    }
    val end = (lastVisible + ahead).coerceAtMost(entries.lastIndex)
    for (index in (lastVisible + 1)..end) {
        if (GalleryGridScrollGate.isUserScrollingGrid) return
        enqueueAt(index)
    }
    val start = (firstVisible - ahead).coerceAtLeast(0)
    for (index in (firstVisible - 1) downTo start) {
        if (GalleryGridScrollGate.isUserScrollingGrid) return
        enqueueAt(index)
    }
}
