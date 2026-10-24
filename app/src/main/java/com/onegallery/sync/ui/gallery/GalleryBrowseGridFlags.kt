package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.sync.CloudSyncState

/** Flag [GalleryDisplayItem.showInBrowseGrid] sem I/O — usada ao filtrar listas grandes na rolagem. */
fun recomputeShowInBrowseGrid(item: GalleryDisplayItem): Boolean {
    if (GalleryMapper.isAudioFileName(item.displayName)) return false
    if (item.kind == MediaKind.VIDEO) {
        val stub = item.cachedPlaceholderThumb
        val hasVideoThumb = stub != null && VideoThumbnailHelper.isGalleryVideoThumbnailFile(stub)
        if (item.oneDriveItemId != null ||
            item.cloudState == CloudSyncState.CLOUD_ONLY ||
            item.cloudState == CloudSyncState.PENDING_DOWNLOAD
        ) {
            if (item.videoCountsAsLocalBrowseThumb()) return true
            return hasVideoThumb
        }
        if (hasVideoThumb) return true
        return item.videoCountsAsLocalBrowseThumb()
    }
    val stub = item.cachedPlaceholderThumb
    if (stub != null && stub.isFile && stub.length() > 0L) return true
    if (item.cloudState == CloudSyncState.CLOUD_ONLY ||
        item.cloudState == CloudSyncState.PENDING_DOWNLOAD
    ) {
        return false
    }
    return item.imageCountsAsLocalBrowseThumb()
}
