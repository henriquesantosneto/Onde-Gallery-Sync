package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.GalleryThumbnailCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Leitura/gravação de miniaturas para a grade — único ponto fora do sync worker. */
object GalleryThumbStore {

    fun displayFile(
        item: GalleryDisplayItem,
        mediaCache: MediaCacheRepository,
    ): File? = item.resolvedGalleryGridThumbFile(mediaCache)

    suspend fun ensureCachedForGrid(
        item: GalleryDisplayItem,
        thumbCache: GalleryThumbnailCache,
        mediaCache: MediaCacheRepository,
    ): File? = withContext(Dispatchers.IO) {
        val cloudOnly = item.isCloudBackedGalleryItem() && item.localUri == null
        thumbCache.ensureThumbnailCached(
            canonicalPath = item.canonicalPath,
            preferCloudFirst = cloudOnly,
            cloudVideoAggressive = item.kind == com.onegallery.sync.data.local.MediaKind.VIDEO,
            lightweightPrefetch = false,
        )
        displayFile(item, mediaCache)
    }
}
