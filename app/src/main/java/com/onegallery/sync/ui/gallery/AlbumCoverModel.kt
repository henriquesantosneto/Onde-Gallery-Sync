package com.onegallery.sync.ui.gallery

/** Capa de álbum: mesma prioridade da grade (local → original em cache → miniatura na nuvem). */
fun resolveAlbumCoverModel(items: List<GalleryDisplayItem>): Any? {
    val coverItem = items.maxByOrNull { it.sortTimestamp } ?: return null
    return resolveGalleryCoverModel(coverItem)
}

fun resolveGalleryCoverModel(item: GalleryDisplayItem): Any? {
    item.localUri?.let { return it }
    item.cachedOriginal?.takeIf { it.exists() }?.let { return it }
    item.cachedPlaceholderThumb?.takeIf { it.exists() }?.let { return it }
    return item.thumbnailModel
}
