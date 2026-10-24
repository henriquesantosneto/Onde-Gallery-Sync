package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState

/**
 * Grade: mídia local imediata; itens indexados na nuvem aparecem com placeholder enquanto
 * a miniatura baixa. Só-local sem nuvem exige miniatura ou URI.
 */
fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (hasBrowsableThumbnail(mediaCacheRepository)) return true
    if (showInBrowseGrid && kind == MediaKind.IMAGE) return true
    if (localUri != null && kind == MediaKind.IMAGE) return true
    if (cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null) return true
    if (kind == MediaKind.VIDEO) return false
    if (localMediaId != null && !localReleasedForSpace) return true
    if (!oneDriveItemId.isNullOrBlank()) return false
    return cloudState != CloudSyncState.LOCAL_ONLY
}

/** Com [browseOnlyItemsWithThumbnail]: exige miniatura ou foto/vídeo local exibível na hora. */
fun GalleryDisplayItem.isVisibleInGalleryBrowseWithThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    return hasBrowsableThumbnail(mediaCacheRepository)
}

/** Miniatura real (JPEG) ou URI local que a grade consegue pintar imediatamente. */
fun GalleryDisplayItem.hasBrowsableThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (hasCachedThumbnailFile(this, mediaCacheRepository)) return true
    if (localReleasedForSpace) return false
    if (kind == MediaKind.VIDEO) {
        if (localUri == null) return false
        if (cloudState == CloudSyncState.CLOUD_ONLY ||
            cloudState == CloudSyncState.PENDING_DOWNLOAD
        ) {
            return false
        }
        return true
    }
    if (localUri != null && kind == MediaKind.IMAGE) return true
    if (kind == MediaKind.IMAGE &&
        cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null
    ) {
        return true
    }
    return false
}
