package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.local.db.UserAlbumEntity

/**
 * Camada única de decisão: **reutilizar sessão** (RAM/spill) vs **rebuild** do catálogo.
 * Separada do sync — o índice Room só invalida quando [GalleryCatalogFingerprint.matchesIndex] falha.
 */
object GalleryLibrarySession {

    data class ResolvedCatalog(
        val bundle: GalleryCatalogSession.SnapshotBundle,
        val source: Source,
    ) {
        enum class Source {
            /** Processo ou spill hidratado na abertura. */
            SESSION_CACHE,
            /** [GalleryCatalogSession.lastBundle] na mesma VM. */
            IN_MEMORY_SESSION,
            /** Montagem completa (IO pesado). */
            REBUILT,
        }
    }

    fun fingerprintOf(metadata: List<SyncMetadataEntity>): GalleryCatalogFingerprint =
        GalleryCatalogFingerprint.from(metadata)

    /**
     * Tenta RAM/spill antes de remontar milhares de [GalleryDisplayItem].
     * Ignora `localRevision` e diferenças só de timestamp — overlay local é opcional depois.
     */
    fun resolveFromSessionCache(
        metadata: List<SyncMetadataEntity>,
        userAlbums: List<UserAlbumEntity>,
        localRevision: Long,
        processCache: GalleryProcessCatalogCache,
    ): ResolvedCatalog? {
        val entry = processCache.peek() ?: return null
        if (!entry.isRestorable()) return null
        val fingerprint = fingerprintOf(metadata)
        if (!entry.fingerprint.matchesIndex(fingerprint)) return null
        val bundle = entry.toSnapshotBundle().copy(
            userAlbums = userAlbums,
            localRevision = localRevision,
            fingerprint = fingerprint,
        )
        return ResolvedCatalog(bundle, ResolvedCatalog.Source.SESSION_CACHE)
    }

    fun resolveFromSessionCacheWhenIndexEmpty(
        userAlbums: List<UserAlbumEntity>,
        localRevision: Long,
        processCache: GalleryProcessCatalogCache,
    ): ResolvedCatalog? {
        val entry = processCache.peek()?.takeIf { it.isRestorable() } ?: return null
        val bundle = entry.toSnapshotBundle().copy(
            userAlbums = userAlbums.ifEmpty { entry.userAlbums },
            localRevision = localRevision,
        )
        return ResolvedCatalog(bundle, ResolvedCatalog.Source.SESSION_CACHE)
    }

    fun resolveFromInMemorySession(
        metadata: List<SyncMetadataEntity>,
        userAlbums: List<UserAlbumEntity>,
        localRevision: Long,
        catalogSession: GalleryCatalogSession,
    ): ResolvedCatalog? {
        val cached = catalogSession.lastBundle ?: return null
        val fingerprint = fingerprintOf(metadata)
        if (!cached.fingerprint.matchesIndex(fingerprint)) return null
        if (cached.userAlbums != userAlbums) return null
        if (cached.catalogItems.isEmpty() && cached.visibleItems.isEmpty()) return null
        val bundle = cached.copy(localRevision = localRevision)
        return ResolvedCatalog(bundle, ResolvedCatalog.Source.IN_MEMORY_SESSION)
    }

    fun shouldSkipFullRebuild(
        metadata: List<SyncMetadataEntity>,
        resolved: ResolvedCatalog,
    ): Boolean {
        if (metadata.isEmpty()) return true
        val fingerprint = fingerprintOf(metadata)
        if (!resolved.bundle.fingerprint.matchesIndex(fingerprint)) return false
        val visible = resolved.bundle.visibleItems.size
        val catalog = resolved.bundle.catalogItems.size
        if (visible >= metadata.size || catalog >= metadata.size) return true
        // Spill parcial (ex.: 8k): índice igual → não remontar 21k só para completar na abertura.
        return visible >= (metadata.size * 99) / 100
    }
}
