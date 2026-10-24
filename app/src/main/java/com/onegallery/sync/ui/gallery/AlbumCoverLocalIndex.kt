package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Capas de álbum só com fontes locais (URI, original em cache, pasta `thumbs`).
 * Não usa rede. Invalida em lote quando o app sinaliza novas miniaturas (debounce).
 */
class AlbumCoverLocalIndex {

    private data class CachedCover(
        val selectionSignature: Long,
        val coverModel: Any,
    )

    private val byKey = ConcurrentHashMap<String, CachedCover>()

    fun invalidateAfterThumbnailBatch() {
        byKey.clear()
    }

    fun invalidateKey(cacheKey: String) {
        byKey.remove(cacheKey)
    }

    fun coverModelForKey(
        cacheKey: String,
        items: List<GalleryDisplayItem>,
        mediaCacheRepository: MediaCacheRepository,
    ): Any? {
        if (items.isEmpty()) return null
        val live = computeLiveCover(items, mediaCacheRepository) ?: return null
        val cached = byKey[cacheKey]
        if (cached != null && cached.selectionSignature == live.selectionSignature) {
            return cached.coverModel
        }
        byKey[cacheKey] = live
        return live.coverModel
    }

    private fun computeLiveCover(
        items: List<GalleryDisplayItem>,
        mediaCacheRepository: MediaCacheRepository,
    ): CachedCover? {
        val item = resolveAlbumCoverItem(items, mediaCacheRepository) ?: return null
        val model = resolveGalleryCoverModel(item, mediaCacheRepository) ?: return null
        return CachedCover(
            selectionSignature = albumCoverSelectionSignature(items, mediaCacheRepository),
            coverModel = model,
        )
    }
}
