package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import java.io.File

data class GalleryDisplayItem(
    val canonicalPath: String,
    val displayName: String,
    val albumName: String,
    val kind: MediaKind,
    val cloudState: CloudSyncState,
    val localMediaId: Long?,
    val oneDriveItemId: String?,
    val pendingDeleteTarget: PendingDeleteTarget,
    val localUri: Uri?,
    val cachedOriginal: File?,
    val cachedPlaceholderThumb: File? = null,
    val sortTimestamp: Long,
) {
    val thumbnailModel: Any?
        get() = localUri ?: cachedOriginal ?: cachedPlaceholderThumb

    val isOfflineAvailable: Boolean
        get() = localUri != null || (cachedOriginal?.exists() == true)

    val showCloudBadge: Boolean
        get() = cloudState == CloudSyncState.CLOUD_ONLY ||
            cloudState == CloudSyncState.PENDING_DOWNLOAD ||
            cloudState == CloudSyncState.PENDING_UPLOAD

    val showSyncedBadge: Boolean
        get() = cloudState == CloudSyncState.SYNCED
}
