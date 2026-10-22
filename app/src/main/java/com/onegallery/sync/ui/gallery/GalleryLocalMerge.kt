package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.SyncPaths

/**
 * Fotos/vídeos ainda não indexados pelo sync entram na galeria pelo MediaStore;
 * quando o índice Room incluir o mesmo item, [dedupeGalleryDisplayItems] mantém a linha indexada.
 */
fun mergeIndexedGalleryWithLocalMedia(
    indexed: List<GalleryDisplayItem>,
    localItems: List<LocalMediaItem>,
    mediaCacheRepository: MediaCacheRepository,
): List<GalleryDisplayItem> {
    if (localItems.isEmpty()) return indexed
    val indexedLocalIds = indexed.mapNotNull { it.localMediaId }.toHashSet()
    val indexedPaths = indexed.map { it.canonicalPath }.toHashSet()
    val localOnly = ArrayList<GalleryDisplayItem>(minOf(localItems.size, 512))
    for (local in localItems) {
        if (local.id in indexedLocalIds) continue
        val path = SyncPaths.canonicalFromLocal(local)
        if (path in indexedPaths) continue
        localOnly.add(GalleryMapper.toDisplayItemFromLocal(local, mediaCacheRepository))
    }
    if (localOnly.isEmpty()) return indexed
    return dedupeGalleryDisplayItems(indexed + localOnly)
}
