package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind

/**
 * A grade só lista foto/vídeo que **já tem JPEG no cache**.
 * Quem grava essa miniatura é o sincronismo — a rolagem não baixa nada.
 */
fun GalleryDisplayItem.appearsInOneDriveGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (kind != MediaKind.IMAGE && kind != MediaKind.VIDEO) return false
    return hasCachedThumbnailFile(this, mediaCacheRepository)
}

fun filterOneDriveGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    if (items.isEmpty()) return items
    return items.filter { it.appearsInOneDriveGalleryBrowse(mediaCacheRepository) }
}

fun recomputeOneDriveShowInBrowseGrid(item: GalleryDisplayItem): Boolean =
    item.hasStubBrowseThumbnailFile()
