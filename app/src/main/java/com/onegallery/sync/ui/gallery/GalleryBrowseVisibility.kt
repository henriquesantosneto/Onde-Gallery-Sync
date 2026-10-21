package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.CloudSyncState

/**
 * Itens indexados na nuvem entram na grade/álbuns assim que constam no índice (placeholder + download
 * da miniatura ao rolar). Local continua visível pelo URI / original / miniatura em cache.
 */
fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (localUri != null) return true
    if (cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null) return true
    if (localMediaId != null && !localReleasedForSpace) return true
    if (oneDriveItemId != null) return true
    if (cloudState == CloudSyncState.LOCAL_ONLY) {
        return hasCachedThumbnailFile(this, mediaCacheRepository)
    }
    return hasCachedThumbnailFile(this, mediaCacheRepository)
}
