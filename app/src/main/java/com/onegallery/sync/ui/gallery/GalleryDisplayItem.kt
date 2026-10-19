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
    val locationLabel: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val videoDurationMs: Long? = null,
    val localReleasedForSpace: Boolean = false,
) {
    val thumbnailModel: Any?
        get() = when (cloudState) {
            CloudSyncState.CLOUD_ONLY, CloudSyncState.PENDING_DOWNLOAD ->
                cachedPlaceholderThumb ?: localUri ?: cachedOriginal
            else -> localUri ?: cachedOriginal ?: cachedPlaceholderThumb
        }

    val isOfflineAvailable: Boolean
        get() = localUri != null || (cachedOriginal?.exists() == true)

    val showCloudBadge: Boolean
        get() = cloudState == CloudSyncState.CLOUD_ONLY ||
            cloudState == CloudSyncState.PENDING_DOWNLOAD ||
            cloudState == CloudSyncState.PENDING_UPLOAD ||
            (localReleasedForSpace && oneDriveItemId != null && localUri == null)

    val showSyncedBadge: Boolean
        get() = cloudState == CloudSyncState.SYNCED

    /** Heurística rápida na grade; a decisão final fica no [SyncCoordinator.needsOriginalRecall]. */
    fun needsCloudOriginalRecall(): Boolean {
        if (oneDriveItemId == null) return false
        if (localReleasedForSpace) return true
        if (cloudState == CloudSyncState.CLOUD_ONLY) return true
        if (cloudState == CloudSyncState.PENDING_DOWNLOAD) return true
        return localMediaId == null && localUri == null
    }
}
