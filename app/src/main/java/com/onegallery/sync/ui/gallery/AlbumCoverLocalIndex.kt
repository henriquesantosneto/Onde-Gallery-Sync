package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Capas de álbum só com fontes locais (URI, original em cache, pasta `thumbs`).
 * Não usa rede. Invalida em lote quando o app sinaliza novas miniaturas (debounce).
 */
class AlbumCoverLocalIndex(
    private val choices: AlbumCoverChoiceStore = AlbumCoverChoiceStore(),
) {

    private data class CachedCover(
        val selectionSignature: Long,
        val coverModel: Any,
    )

    private val byKey = ConcurrentHashMap<String, CachedCover>()

    fun loadChoices(file: File) {
        choices.load(file)
    }

    fun saveChoicesIfDirty(file: File) {
        choices.saveIfDirty(file)
    }

    fun invalidateAfterThumbnailBatch() {
        byKey.clear()
    }

    fun invalidateKey(cacheKey: String) {
        byKey.remove(cacheKey)
    }

    /** Capas cuja foto foi excluída. O mapa devolvido serve para desfazer se a exclusão for cancelada. */
    fun forgetCoverPaths(paths: Set<String>): Map<String, AlbumCoverChoice> {
        val dropped = choices.forgetPaths(paths)
        dropped.keys.forEach { byKey.remove(it) }
        return dropped
    }

    fun restoreCoverChoices(remembered: Map<String, AlbumCoverChoice>) {
        choices.restore(remembered)
        remembered.keys.forEach { byKey.remove(it) }
    }

    fun stickyCoverItem(
        cacheKey: String,
        items: List<GalleryDisplayItem>,
        mediaCacheRepository: MediaCacheRepository,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): GalleryDisplayItem? {
        if (items.isEmpty()) return null
        return choices.choose(cacheKey, items, nowEpochMs) { item ->
            item.hasCheapAlbumCoverSource(mediaCacheRepository)
        }
    }

    /** Assinatura estável enquanto a mesma foto continuar sendo a capa. */
    fun stickyCoverSignature(
        cacheKey: String,
        items: List<GalleryDisplayItem>,
        mediaCacheRepository: MediaCacheRepository,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Long {
        val cover = stickyCoverItem(cacheKey, items, mediaCacheRepository, nowEpochMs) ?: return 0L
        return signatureOf(cover)
    }

    private fun signatureOf(cover: GalleryDisplayItem): Long {
        var hash = cover.canonicalPath.hashCode().toLong()
        hash = hash * 31L + cover.sortTimestamp
        hash = hash * 31L + if (cover.cachedPlaceholderThumb != null || cover.localUri != null) 1L else 0L
        return hash
    }

    fun coverModelForKey(
        cacheKey: String,
        items: List<GalleryDisplayItem>,
        mediaCacheRepository: MediaCacheRepository,
    ): Any? {
        if (items.isEmpty()) return null
        val item = stickyCoverItem(cacheKey, items, mediaCacheRepository) ?: return null
        val signature = signatureOf(item)
        val cached = byKey[cacheKey]
        if (cached != null &&
            cached.selectionSignature == signature &&
            cached.coverModel.isPaintableAlbumCoverModel()
        ) {
            return cached.coverModel
        }
        val model = resolveGalleryCoverModel(item, mediaCacheRepository)?.takeIf {
            it.isPaintableAlbumCoverModel()
        } ?: fallbackAlbumCoverFile(items, mediaCacheRepository) ?: return null
        byKey[cacheKey] = CachedCover(signature, model)
        return model
    }
}

/** A foto mais nova do álbum pode ainda não ter JPEG. Usa outro arquivo já gravado. */
internal fun fallbackAlbumCoverFile(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
    limit: Int = 32,
): java.io.File? {
    val recent = if (items.size <= limit) {
        items
    } else {
        items.sortedByDescending { it.sortTimestamp }.take(limit)
    }
    for (candidate in recent) {
        val model = resolveGalleryCoverModel(candidate, mediaCacheRepository)
        if (model is java.io.File && model.isPaintableAlbumCoverModel()) return model
    }
    return null
}
