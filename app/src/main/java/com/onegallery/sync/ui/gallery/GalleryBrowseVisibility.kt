package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.CloudSyncState

/**
 * Itens só na nuvem (ou com local liberado) entram na grade/álbuns **após** miniatura no cache.
 * Mídia local continua visível pelo URI / original.
 */
fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (localUri != null) return true
    if (cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null) return true
    if (localMediaId != null && !localReleasedForSpace) return true
    if (cloudState == CloudSyncState.LOCAL_ONLY && oneDriveItemId == null) {
        return hasCachedThumbnailFile(this, mediaCacheRepository)
    }
    return hasCachedThumbnailFile(this, mediaCacheRepository)
}
