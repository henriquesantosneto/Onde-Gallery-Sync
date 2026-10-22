package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.UserAlbumEntity

/**
 * Grade imediata só com MediaStore — antes do Room ter linhas (sem esperar sync bidirecional).
 */
object GalleryLocalBootstrap {

    suspend fun buildSnapshotBundle(
        localMediaRepository: LocalMediaRepository,
        mediaCacheRepository: MediaCacheRepository,
        userAlbums: List<UserAlbumEntity>,
        localRevision: Long,
    ): GalleryCatalogSession.SnapshotBundle? {
        val localItems = localMediaRepository.loadLocalGallerySnapshot()
        if (localItems.isEmpty()) return null
        val display = localItems.map { GalleryMapper.toDisplayItemFromLocal(it, mediaCacheRepository) }
        val visible = display.filter { it.isVisibleInGalleryBrowse(mediaCacheRepository) }
        if (visible.isEmpty()) return null
        val catalog = display
        val fingerprint = GalleryCatalogFingerprint.fromDisplayPaths(catalog.map { it.canonicalPath })
        return GalleryCatalogSession.SnapshotBundle(
            catalogItems = catalog,
            visibleItems = if (visible.isNotEmpty()) visible else catalog,
            userAlbums = userAlbums,
            fingerprint = fingerprint,
            localRevision = localRevision,
        )
    }
}
