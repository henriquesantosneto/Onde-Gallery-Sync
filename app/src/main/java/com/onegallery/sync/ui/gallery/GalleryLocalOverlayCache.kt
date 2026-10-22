package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.SyncPaths

/** Itens só no MediaStore ainda não indexados — recalcula só quando [localRevision] muda. */
class GalleryLocalOverlayCache {
    private var revision: Long = -1L
    private var overlay: List<GalleryDisplayItem> = emptyList()

    suspend fun overlayItems(
        indexed: List<GalleryDisplayItem>,
        localRevision: Long,
        localMediaRepository: LocalMediaRepository,
        mediaCacheRepository: MediaCacheRepository,
    ): List<GalleryDisplayItem> {
        if (revision == localRevision) return overlay
        val localItems = localMediaRepository.loadLocalGallerySnapshot()
        if (localItems.isEmpty()) {
            revision = localRevision
            overlay = emptyList()
            return overlay
        }
        val indexedLocalIds = indexed.mapNotNull { it.localMediaId }.toHashSet()
        val indexedPaths = indexed.map { it.canonicalPath }.toHashSet()
        val localOnly = ArrayList<GalleryDisplayItem>(64)
        for (local in localItems) {
            if (local.id in indexedLocalIds) continue
            val path = SyncPaths.canonicalFromLocal(local)
            if (path in indexedPaths) continue
            localOnly.add(GalleryMapper.toDisplayItemFromLocal(local, mediaCacheRepository))
        }
        revision = localRevision
        overlay = localOnly
        return overlay
    }

    fun invalidate() {
        revision = -1L
        overlay = emptyList()
    }
}
