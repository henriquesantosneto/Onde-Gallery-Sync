package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind

/**
 * Galeria integrada OneDrive: itens do índice aparecem na grade; miniatura vem do sync/prefetch.
 */
fun GalleryDisplayItem.appearsInOneDriveGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (kind != MediaKind.IMAGE && kind != MediaKind.VIDEO) return false
    if (localReleasedForSpace) {
        if (resolvedGalleryGridThumbFile(mediaCacheRepository) != null) return true
        return mediaCacheRepository.displayableThumbnailIfCached(
            canonicalPath,
            oneDriveItemId,
        ) != null
    }
    if (!oneDriveItemId.isNullOrBlank()) return true
    if (localUri != null || localMediaId != null) return true
    val orig = cachedOriginal
    return orig != null && orig.isFile && orig.length() > 0L
}

fun filterOneDriveGalleryBrowse(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    onlyItemsWithThumbnail: Boolean,
): List<GalleryDisplayItem> {
    if (items.isEmpty()) return items
    return items.filter { item ->
        if (!item.appearsInOneDriveGalleryBrowse(mediaCacheRepository)) return@filter false
        if (!onlyItemsWithThumbnail) return@filter true
        if (hasCachedThumbnailFile(item, mediaCacheRepository)) return@filter true
        if (item.localUri != null && item.kind == MediaKind.IMAGE) return@filter true
        if (item.hasLocalVideoCoverOnDevice()) return@filter true
        return@filter !item.oneDriveItemId.isNullOrBlank()
    }
}

fun recomputeOneDriveShowInBrowseGrid(item: GalleryDisplayItem): Boolean {
    if (GalleryMapper.isAudioFileName(item.displayName)) return false
    if (item.kind != MediaKind.IMAGE && item.kind != MediaKind.VIDEO) return false
    if (item.localReleasedForSpace) return false
    return !item.oneDriveItemId.isNullOrBlank() ||
        item.localUri != null ||
        item.localMediaId != null ||
        (item.cachedOriginal?.isFile == true && (item.cachedOriginal?.length() ?: 0L) > 0L)
}
