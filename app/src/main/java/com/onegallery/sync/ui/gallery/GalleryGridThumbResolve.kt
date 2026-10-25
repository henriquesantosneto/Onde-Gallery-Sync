package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File

internal fun GalleryDisplayItem.resolvedGalleryGridThumbFile(
    mediaCache: MediaCacheRepository,
): File? {
    return when (kind) {
        MediaKind.VIDEO -> resolvedGridVideoThumbFile(mediaCache)
        MediaKind.IMAGE -> {
            mediaCache.displayableThumbnailIfCached(canonicalPath, oneDriveItemId)
                ?: cachedPlaceholderThumb?.takeIf {
                    mediaCache.isDisplayableGalleryThumbFile(it)
                }
        }
        else -> null
    }
}

internal fun GalleryDisplayItem.isCloudBackedGalleryItem(): Boolean =
    !oneDriveItemId.isNullOrBlank() ||
        cloudState == com.onegallery.sync.sync.CloudSyncState.CLOUD_ONLY ||
        cloudState == com.onegallery.sync.sync.CloudSyncState.PENDING_DOWNLOAD
