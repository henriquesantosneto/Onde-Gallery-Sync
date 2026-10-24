package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState

/**
 * Grade sem filtro de miniatura: fotos e vídeos locais podem usar URI; nuvem exige JPEG/poster.
 */
fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (hasBrowsableThumbnail(mediaCacheRepository)) return true
    if (kind == MediaKind.VIDEO) return false
    if (localReleasedForSpace) return false
    if (cloudState == CloudSyncState.CLOUD_ONLY ||
        cloudState == CloudSyncState.PENDING_DOWNLOAD
    ) {
        return false
    }
    if (localMediaId != null && !localReleasedForSpace && oneDriveItemId.isNullOrBlank()) {
        return true
    }
    if (!oneDriveItemId.isNullOrBlank()) return false
    return cloudState != CloudSyncState.LOCAL_ONLY
}

/** Com [browseOnlyItemsWithThumbnail]: exige miniatura ou foto/vídeo local exibível na hora. */
fun GalleryDisplayItem.isVisibleInGalleryBrowseWithThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    return hasBrowsableThumbnail(mediaCacheRepository)
}

/** Vídeo na nuvem: só JPEG/poster; vídeo no aparelho: cache ou URI local (frame na grade). */
fun GalleryDisplayItem.hasBrowsableThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (hasCachedThumbnailFile(this, mediaCacheRepository)) return true
    if (localReleasedForSpace) return false
    if (kind == MediaKind.VIDEO) return videoCountsAsLocalBrowseThumb()
    if (kind == MediaKind.IMAGE) return imageCountsAsLocalBrowseThumb()
    return false
}

/** Foto no aparelho ou cópia em cache; nuvem sem JPEG não entra na grade. */
fun GalleryDisplayItem.imageCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.IMAGE) return false
    if (localReleasedForSpace) return false
    if (cloudState == CloudSyncState.CLOUD_ONLY ||
        cloudState == CloudSyncState.PENDING_DOWNLOAD
    ) {
        return false
    }
    if (cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null) return true
    return localUri != null
}

/** Vídeo ainda no aparelho — miniatura imediata via MediaStore (não conta só na nuvem). */
fun GalleryDisplayItem.videoCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.VIDEO) return false
    if (localReleasedForSpace) return false
    if (cloudState == CloudSyncState.CLOUD_ONLY ||
        cloudState == CloudSyncState.PENDING_DOWNLOAD
    ) {
        return false
    }
    if (localUri == null) return false
    if (!oneDriveItemId.isNullOrBlank() && cloudState != CloudSyncState.LOCAL_ONLY) {
        val hasLocalCopy = cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null
        if (!hasLocalCopy) return false
    }
    return true
}
