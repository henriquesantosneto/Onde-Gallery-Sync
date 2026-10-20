package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import java.io.File

/** Capa resolvida ao rolar a grade (modelo + item de origem, ex. vídeo). */
data class LiveAlbumCover(
    val displayModel: Any?,
    val sourceItem: GalleryDisplayItem?,
)

/**
 * Capa de álbum: mídia **mais recente** do grupo que já tenha fonte local para exibir.
 * Consulta a pasta de miniaturas ao vivo — não depende do snapshot da galeria nem do fim do sync.
 */
fun resolveAlbumCoverItem(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): GalleryDisplayItem? {
    return items
        .asSequence()
        .filter { galleryItemCoverReady(it, mediaCacheRepository) }
        .maxWithOrNull(albumCoverItemComparator)
}

fun resolveAlbumCoverModel(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): Any? {
    return resolveAlbumCoverItem(items, mediaCacheRepository)?.let {
        resolveGalleryCoverModel(it, mediaCacheRepository)
    }
}

private val albumCoverItemComparator = compareBy<GalleryDisplayItem>(
    { it.sortTimestamp },
    { item ->
        item.cachedPlaceholderThumb?.takeIf { it.isFile }?.lastModified()
            ?: 0L
    },
)

fun galleryItemCoverReady(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    return resolveGalleryCoverModel(item, mediaCacheRepository) != null
}

fun resolveGalleryCoverModel(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository? = null,
): Any? {
    val cachedThumb = mediaCacheRepository?.thumbnailIfCached(item.canonicalPath)
        ?.takeIf { it.isFile && it.length() > 0L }
        ?: item.cachedPlaceholderThumb?.takeIf { it.isFile && it.length() > 0L }
    if (item.kind == MediaKind.VIDEO) {
        cachedThumb?.let { return it }
        item.cachedOriginal?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
        item.localUri?.let { return it }
        return item.thumbnailModel
    }
    item.localUri?.let { return it }
    item.cachedOriginal?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
    cachedThumb?.let { return it }
    return item.thumbnailModel
}

fun resolveLiveAlbumCover(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    fallbackModel: Any? = null,
): LiveAlbumCover {
    val item = resolveAlbumCoverItem(items, mediaCacheRepository)
    val model = item?.let { resolveGalleryCoverModel(it, mediaCacheRepository) } ?: fallbackModel
    return LiveAlbumCover(displayModel = model, sourceItem = item)
}
