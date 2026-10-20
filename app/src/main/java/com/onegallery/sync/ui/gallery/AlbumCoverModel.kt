package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import java.io.File

/**
 * Capa de álbum: usa a mídia mais recente que **já** tenha algo para exibir (URI local, original
 * em cache ou miniatura gerada). Não espera a foto mais nova do álbum ganhar miniatura no sync.
 */
fun resolveAlbumCoverItem(items: List<GalleryDisplayItem>): GalleryDisplayItem? {
    return items
        .asSequence()
        .mapNotNull { item -> resolveGalleryCoverModel(item)?.let { item to it } }
        .maxWithOrNull(albumCoverItemComparator)
        ?.first
}

fun resolveAlbumCoverModel(items: List<GalleryDisplayItem>): Any? {
    return resolveAlbumCoverItem(items)?.let { resolveGalleryCoverModel(it) }
}

/** Ordem: data da mídia (mais recente primeiro), depois miniatura local gerada mais recente. */
private val albumCoverItemComparator = compareBy<Pair<GalleryDisplayItem, Any>> { it.first.sortTimestamp }
    .thenBy { (_, model) ->
        when (model) {
            is File -> model.lastModified()
            else -> 0L
        }
    }

fun galleryItemCoverReady(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository? = null,
): Boolean {
    if (resolveGalleryCoverModel(item) != null) return true
    return mediaCacheRepository?.thumbnailIfCached(item.canonicalPath)?.isFile == true
}

fun resolveGalleryCoverModel(item: GalleryDisplayItem): Any? {
    item.localUri?.let { return it }
    item.cachedOriginal?.takeIf { it.exists() }?.let { return it }
    item.cachedPlaceholderThumb?.takeIf { it.exists() }?.let { return it }
    return item.thumbnailModel
}
