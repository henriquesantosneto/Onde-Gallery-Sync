package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.db.SyncMetadataEntity

fun filterVisibleForGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    if (items.isEmpty()) return items
    return items.filter { item ->
        when {
            item.kind == MediaKind.VIDEO ->
                item.hasBrowsableThumbnail(mediaCacheRepository)
            onlyItemsWithThumbnail || items.size >= 1_500 ->
                item.showInBrowseGrid
            else ->
                item.isVisibleInGalleryBrowse(mediaCacheRepository)
        }
    }
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
