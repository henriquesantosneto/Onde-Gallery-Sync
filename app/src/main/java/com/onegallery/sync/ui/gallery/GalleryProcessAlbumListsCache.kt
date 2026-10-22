package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaAlbum

/** Listas de álbuns já montadas na sessão (RAM + spill ao ir para 2º plano). */
class GalleryProcessAlbumListsCache {
    data class Entry(
        val albums: List<MediaAlbum>,
        val placeAlbums: List<MediaAlbum>,
        val fingerprint: GalleryCatalogFingerprint,
        val localRevision: Long,
        val albumListSort: AlbumListSort,
        val coverRevisionStamp: Long,
    ) {
        fun isRestorable(): Boolean = albums.isNotEmpty() || placeAlbums.isNotEmpty()
    }

    @Volatile
    private var entry: Entry? = null

    fun save(entry: Entry) {
        if (!entry.isRestorable()) return
        this.entry = entry
    }

    fun peek(): Entry? = entry

    fun replace(entry: Entry) {
        this.entry = entry
    }

    fun clear() {
        entry = null
    }
}
