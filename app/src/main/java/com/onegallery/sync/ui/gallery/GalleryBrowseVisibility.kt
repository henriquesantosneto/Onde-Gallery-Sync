package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind

fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean = appearsInOneDriveGalleryBrowse(mediaCacheRepository)

fun GalleryDisplayItem.isVisibleInGalleryBrowseWithThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean = appearsInOneDriveGalleryBrowse(mediaCacheRepository)

fun GalleryDisplayItem.hasBrowsableThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean = appearsInOneDriveGalleryBrowse(mediaCacheRepository)

/**
 * A aba Fotos já recebe a lista filtrada no índice.
 * Consultar o disco de novo, item a item, trava a tela e pede para aguardar ou fechar.
 */
fun GalleryDisplayItem.belongsOnBrowseGridWithoutDisk(): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (kind != MediaKind.IMAGE && kind != MediaKind.VIDEO) return false
    if (isLocalPhotoOnDevice() || hasLocalVideoCoverOnDevice()) return true
    if (!showInBrowseGrid) return false
    return cachedPlaceholderThumb != null
}

fun GalleryDisplayItem.imageCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.IMAGE) return false
    return mediaCountsAsLocalBrowseThumb()
}

fun GalleryDisplayItem.videoCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.VIDEO) return false
    return mediaCountsAsLocalBrowseThumb()
}
