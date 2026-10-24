package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataEntity

fun filterVisibleForGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    if (items.isEmpty()) return items
    if (onlyItemsWithThumbnail || items.size >= 1_500) {
        return items.filter { it.showInBrowseGrid }
    }
    return items.filter { it.isVisibleInGalleryBrowse(mediaCacheRepository) }
}

fun mapMetadataToBrowsableDisplayItems(
    metadata: List<SyncMetadataEntity>,
    localMediaRepository: LocalMediaRepository,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean,
): List<GalleryDisplayItem> {
    if (metadata.isEmpty()) return emptyList()
    val mapped = metadata.map { entity ->
        GalleryMapper.toDisplayItem(entity, mediaCacheRepository, localMediaRepository)
    }
    return filterVisibleForGalleryBrowse(
        mapped,
        mediaCacheRepository,
        onlyItemsWithThumbnail,
    )
}
