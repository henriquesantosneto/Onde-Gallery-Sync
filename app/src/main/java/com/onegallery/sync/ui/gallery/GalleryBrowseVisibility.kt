package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.CloudSyncState

/**
 * Grade: mídia local imediata; itens indexados na nuvem aparecem com placeholder enquanto
 * a miniatura baixa. Só-local sem nuvem exige miniatura ou URI.
 */
fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (localUri != null) return true
    if (cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null) return true
    if (localMediaId != null && !localReleasedForSpace) return true
    if (!oneDriveItemId.isNullOrBlank()) return true
    if (hasCachedThumbnailFile(this, mediaCacheRepository)) return true
    return cloudState != CloudSyncState.LOCAL_ONLY
}
