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
    return items.mapNotNull { it.preparedForBrowseGrid(mediaCacheRepository) }
}

/**
 * A contagem do álbum olha o JPEG no disco. A linha do índice pode ter sido montada
 * antes desse arquivo existir (`showInBrowseGrid` falso). Gruda o arquivo aqui,
 * fora da rolagem, para a lista de dentro usar o mesmo conjunto da contagem.
 */
fun GalleryDisplayItem.preparedForBrowseGrid(
    mediaCacheRepository: MediaCacheRepository,
): GalleryDisplayItem? {
    if (GalleryMapper.isAudioFileName(displayName)) return null
    if (kind != MediaKind.IMAGE && kind != MediaKind.VIDEO) return null
    if (isLocalPhotoOnDevice() || hasLocalVideoCoverOnDevice()) {
        return if (showInBrowseGrid) this else copy(showInBrowseGrid = true)
    }
    val file = cachedBrowseThumbFile(this, mediaCacheRepository) ?: return null
    if (showInBrowseGrid && cachedPlaceholderThumb == file) return this
    return copy(showInBrowseGrid = true, cachedPlaceholderThumb = file)
}

fun recomputeOneDriveShowInBrowseGrid(item: GalleryDisplayItem): Boolean =
    item.isLocalPhotoOnDevice() ||
        item.hasLocalVideoCoverOnDevice() ||
        item.hasStubBrowseThumbnailFile()
