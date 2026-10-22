package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository

fun filterVisibleForGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): List<GalleryDisplayItem> {
    if (items.isEmpty()) return items
    if (items.size >= 1_500) {
        val out = ArrayList<GalleryDisplayItem>(items.size)
        for (item in items) {
            if (item.showInBrowseGrid || item.isVisibleInGalleryBrowse(mediaCacheRepository)) {
                out.add(item)
            }
        }
        return out
    }
    return items.filter { it.isVisibleInGalleryBrowse(mediaCacheRepository) }
}
