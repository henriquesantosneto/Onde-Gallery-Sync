package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.local.db.UserAlbumEntity

/** Mesma regra de sessão que [GalleryLibrarySession], para pastas/locais. */
object GalleryAlbumLibrarySession {

    fun resolveFromProcessCache(
        metadata: List<SyncMetadataEntity>,
        userAlbums: List<UserAlbumEntity>,
        albumSort: AlbumListSort,
        albumCache: GalleryProcessAlbumListsCache,
        catalogFingerprint: GalleryCatalogFingerprint?,
    ): GalleryProcessAlbumListsCache.Entry? {
        val entry = albumCache.peek()?.takeIf { it.isRestorable() } ?: return null
        val fingerprint = catalogFingerprint
            ?: if (metadata.isNotEmpty()) GalleryCatalogFingerprint.from(metadata) else entry.fingerprint
        if (!entry.fingerprint.matchesIndex(fingerprint)) return null
        return entry
    }

    fun resolveWhenMetadataEmpty(
        albumCache: GalleryProcessAlbumListsCache,
        catalogCache: GalleryProcessCatalogCache,
    ): GalleryCatalogFingerprint? =
        albumCache.peek()?.fingerprint
            ?: catalogCache.peek()?.fingerprint
}
