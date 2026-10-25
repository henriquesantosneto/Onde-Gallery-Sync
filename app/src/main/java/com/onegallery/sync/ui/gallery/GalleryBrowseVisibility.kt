package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState

/** Mesma regra para foto e vídeo: miniatura em disco ou mídia local exibível. */
fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean = hasBrowsableThumbnail(mediaCacheRepository)

/** Com [browseOnlyItemsWithThumbnail]: exige miniatura ou foto/vídeo local exibível na hora. */
fun GalleryDisplayItem.isVisibleInGalleryBrowseWithThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    return hasBrowsableThumbnail(mediaCacheRepository)
}

/** Vídeo: JPEG no cache, arquivo local no aparelho, ou só nuvem (Coil baixa poster na grade). */
fun GalleryDisplayItem.hasBrowsableThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (hasCachedThumbnailFile(this, mediaCacheRepository)) return true
    if (localReleasedForSpace) return false
    if (kind == MediaKind.VIDEO && isCloudBackedInIndex()) {
        if (hasLocalVideoCoverOnDevice()) return true
        if (localUri == null && !oneDriveItemId.isNullOrBlank()) return true
        return false
    }
    return mediaCountsAsLocalBrowseThumb()
}

fun GalleryDisplayItem.imageCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.IMAGE) return false
    return mediaCountsAsLocalBrowseThumb()
}

fun GalleryDisplayItem.videoCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.VIDEO) return false
    return mediaCountsAsLocalBrowseThumb()
}
