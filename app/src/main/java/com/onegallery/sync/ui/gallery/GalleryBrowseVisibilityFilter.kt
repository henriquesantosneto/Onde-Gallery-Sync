package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository

fun filterVisibleForGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): List<GalleryDisplayItem> {
    if (items.isEmpty()) return items
    return items.filter { it.isVisibleInGalleryBrowse(mediaCacheRepository) }
}
