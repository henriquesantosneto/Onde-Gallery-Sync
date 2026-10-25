package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository

/** Pastas: capa/contagem — inclui vídeo local no aparelho; nuvem sem poster continua fora. */
fun itemsForAlbumListGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    val raw = when {
        visibleItems.isNotEmpty() -> visibleItems
        onlyItemsWithThumbnail -> emptyList()
        else -> catalogItems
    }
    return raw.filter { it.isEligibleForAlbumCover(mediaCacheRepository) }
}

/** Locais: mesma regra da grade. */
fun itemsForPlaceAlbumGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    val raw = when {
        visibleItems.isNotEmpty() -> visibleItems
        onlyItemsWithThumbnail -> emptyList()
        else -> catalogItems
    }
    return raw.filter { it.isEligibleForAlbumCover(mediaCacheRepository) }
}
