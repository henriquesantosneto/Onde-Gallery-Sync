package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaCacheRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Capas de álbum só com fontes locais (URI, original em cache, pasta `thumbs`).
 * Não usa rede. Invalida em lote quando o app sinaliza novas miniaturas (debounce).
 */
class AlbumCoverLocalIndex {

    private data class CachedCover(
        val canonicalPath: String,
        val sortTimestamp: Long,
        val coverModel: Any,
    )

    private val byKey = ConcurrentHashMap<String, CachedCover>()

    fun invalidateAfterThumbnailBatch() {
        byKey.clear()
    }

    fun coverModelForKey(
        cacheKey: String,
        items: List<GalleryDisplayItem>,
        mediaCacheRepository: MediaCacheRepository,
    ): Any? {
        if (items.isEmpty()) return null
        val live = computeLiveCover(items, mediaCacheRepository) ?: return null
        val cached = byKey[cacheKey]
        if (cached != null &&
            cached.canonicalPath == live.canonicalPath &&
            cached.sortTimestamp == live.sortTimestamp &&
            coverModelStillUsable(cached.coverModel, live.canonicalPath, mediaCacheRepository)
        ) {
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
            canonicalPath = item.canonicalPath,
            sortTimestamp = item.sortTimestamp,
            coverModel = model,
        )
    }

    private fun coverModelStillUsable(
        model: Any,
        canonicalPath: String,
        mediaCacheRepository: MediaCacheRepository,
    ): Boolean {
        return when (model) {
            is File -> model.isFile && model.length() > 0L
            is Uri -> true
            else -> mediaCacheRepository.thumbnailIfCached(canonicalPath)?.isFile == true
        }
    }
}
