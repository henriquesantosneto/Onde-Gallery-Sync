package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState

/** Foto que ainda está no aparelho (MediaStore). Não espera JPEG do sync. */
fun GalleryDisplayItem.isLocalPhotoOnDevice(): Boolean {
    if (kind != MediaKind.IMAGE) return false
    if (localReleasedForSpace || localUri == null) return false
    if (cloudState == CloudSyncState.CLOUD_ONLY ||
        cloudState == CloudSyncState.PENDING_DOWNLOAD
    ) {
        return false
    }
    return true
}

/**
 * Grade: JPEG do sincronismo, foto local, ou vídeo que ainda está no aparelho.
 * Item só na nuvem continua fora até o sincronismo gravar a miniatura.
 */
fun GalleryDisplayItem.appearsInOneDriveGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (kind != MediaKind.IMAGE && kind != MediaKind.VIDEO) return false
    if (isLocalPhotoOnDevice()) return true
    if (hasLocalVideoCoverOnDevice()) return true
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
    item.isLocalPhotoOnDevice() ||
        item.hasLocalVideoCoverOnDevice() ||
        item.hasStubBrowseThumbnailFile()
