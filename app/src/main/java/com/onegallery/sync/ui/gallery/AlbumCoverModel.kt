package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaCacheRepository
import java.io.File

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
    item.localUri?.let { return it }
    item.cachedOriginal?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
    mediaCacheRepository?.thumbnailIfCached(item.canonicalPath)?.takeIf { it.isFile }?.let { return it }
    item.cachedPlaceholderThumb?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
    return item.thumbnailModel
}
