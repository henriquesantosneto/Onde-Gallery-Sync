package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.sync.CloudSyncState
import java.io.File

fun GalleryDisplayItem.isCloudBackedInIndex(): Boolean =
    !oneDriveItemId.isNullOrBlank() ||
        cloudState == CloudSyncState.CLOUD_ONLY ||
        cloudState == CloudSyncState.PENDING_DOWNLOAD

/** Foto/vídeo ainda no aparelho (mesmas regras para os dois). */
fun GalleryDisplayItem.mediaCountsAsLocalBrowseThumb(): Boolean {
    if (localReleasedForSpace) return false
    if (cloudState == CloudSyncState.CLOUD_ONLY ||
        cloudState == CloudSyncState.PENDING_DOWNLOAD
    ) {
        return false
    }
    if (localUri == null) return false
    if (kind == MediaKind.IMAGE && needsCloudOriginalRecall()) return false
    if (!oneDriveItemId.isNullOrBlank() && cloudState != CloudSyncState.LOCAL_ONLY) {
        val hasLocalCopy = cachedOriginal?.takeIf { it.isFile && it.length() > 0L } != null
        if (!hasLocalCopy) return false
    }
    return true
}

fun GalleryDisplayItem.hasStubBrowseThumbnailFile(): Boolean {
    val stub = cachedPlaceholderThumb ?: return false
    if (!stub.isFile || stub.length() <= 0L) return false
    return when (kind) {
        MediaKind.VIDEO -> VideoThumbnailHelper.isGalleryVideoThumbnailFile(stub)
        MediaKind.IMAGE ->
            VideoThumbnailHelper.isAcceptableThumbnailFile(stub) && stub.length() >= 128L
        else -> false
    }
}

/** Flag rápida alinhada a [hasBrowsableThumbnail] (sem I/O). */
fun recomputeShowInBrowseGrid(item: GalleryDisplayItem): Boolean {
    if (GalleryMapper.isAudioFileName(item.displayName)) return false
    val hasDiskThumb = item.hasStubBrowseThumbnailFile()
    if (item.isCloudBackedInIndex()) {
        if (item.mediaCountsAsLocalBrowseThumb()) return true
        return hasDiskThumb
    }
    if (hasDiskThumb) return true
    return item.mediaCountsAsLocalBrowseThumb()
}

fun spillShowInBrowseGrid(
    kind: MediaKind,
    cloudState: CloudSyncState,
    placeholder: File?,
    localUri: android.net.Uri?,
    cachedOriginal: File?,
    localReleasedForSpace: Boolean,
    oneDriveItemId: String?,
): Boolean {
    val stubItem = GalleryDisplayItem(
        canonicalPath = "",
        displayName = if (kind == MediaKind.VIDEO) "x.mp4" else "x.jpg",
        albumName = "",
        kind = kind,
        cloudState = cloudState,
        localMediaId = null,
        oneDriveItemId = oneDriveItemId,
        pendingDeleteTarget = com.onegallery.sync.sync.PendingDeleteTarget.NONE,
        localUri = localUri,
        cachedOriginal = cachedOriginal,
        cachedPlaceholderThumb = placeholder,
        sortTimestamp = 0L,
        localReleasedForSpace = localReleasedForSpace,
    )
    return recomputeShowInBrowseGrid(stubItem)
}
