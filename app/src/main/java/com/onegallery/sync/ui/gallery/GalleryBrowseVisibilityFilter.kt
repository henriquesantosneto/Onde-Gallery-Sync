package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataEntity

fun filterVisibleForGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> = filterOneDriveGalleryBrowse(
    items,
    mediaCacheRepository,
    onlyItemsWithThumbnail,
)

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
        dedupeGalleryDisplayItems(mapped),
        mediaCacheRepository,
        onlyItemsWithThumbnail,
    )
}
