package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository

fun filterVisibleForGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    if (items.isEmpty()) return items
    if (onlyItemsWithThumbnail) {
        return items.filter { it.isVisibleInGalleryBrowseWithThumbnail(mediaCacheRepository) }
    }
    if (items.size >= 1_500) {
        val out = ArrayList<GalleryDisplayItem>(items.size)
        for (item in items) {
            if (item.showInBrowseGrid) out.add(item)
        }
        return out
    }
    return items.filter { it.isVisibleInGalleryBrowse(mediaCacheRepository) }
}
