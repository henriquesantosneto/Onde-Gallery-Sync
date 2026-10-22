package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.db.UserAlbumEntity

/**
 * Catálogo montado da última sessão **no mesmo processo** (app em 2º plano / Home).
 * Evita remontar 21k ao voltar — cai quando o Android mata o processo (ex.: remover dos recentes).
 */
class GalleryProcessCatalogCache {
    data class Entry(
        val catalogItems: List<GalleryDisplayItem>,
        val visibleItems: List<GalleryDisplayItem>,
        val userAlbums: List<UserAlbumEntity>,
        val fingerprint: GalleryCatalogFingerprint,
        val localRevision: Long,
    ) {
        /** Índice inteiro montado (ou quase — dedupe pode reduzir 1–2 linhas). */
        val isCompleteCatalog: Boolean
            get() {
                if (visibleItems.isEmpty()) return false
                if (fingerprint.rowCount <= 0) return true
                val threshold = (fingerprint.rowCount * 99) / 100
                return visibleItems.size >= threshold || catalogItems.size >= threshold
            }

        fun isRestorable(): Boolean = visibleItems.isNotEmpty()

        fun toSnapshotBundle(): GalleryCatalogSession.SnapshotBundle =
            GalleryCatalogSession.SnapshotBundle(
                catalogItems = catalogItems,
                visibleItems = visibleItems,
                userAlbums = userAlbums,
                fingerprint = fingerprint,
                localRevision = localRevision,
            )
    }

    @Volatile
    private var entry: Entry? = null

    fun save(bundle: GalleryCatalogSession.SnapshotBundle) {
        if (bundle.catalogItems.isEmpty() && bundle.visibleItems.isEmpty()) return
        entry = Entry(
            catalogItems = bundle.catalogItems,
            visibleItems = bundle.visibleItems,
            userAlbums = bundle.userAlbums,
            fingerprint = bundle.fingerprint,
            localRevision = bundle.localRevision,
        )
    }

    fun peek(): Entry? = entry

    fun replace(entry: Entry) {
        this.entry = entry
    }

    fun clear() {
        entry = null
    }
}
