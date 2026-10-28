package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.onedrive.RemoteDriveFile

/**
 * “Registrando só na nuvem” só entra de novo quando o arquivo ainda não está no índice
 * ou a identidade na nuvem mudou. GPS ausente não recoloca o item na fila a cada sync.
 */
internal fun shouldRegisterCloudOnlyItem(
    path: String,
    remote: RemoteDriveFile,
    existing: SyncMetadataEntity?,
    onDevicePaths: Set<String>,
    userDeletedPaths: Set<String>,
): Boolean {
    if (path in onDevicePaths || path in userDeletedPaths) return false
    if (existing == null) return true
    if (existing.localMediaId == null &&
        (
            existing.oneDriveItemId.isNullOrBlank() ||
                existing.cloudState == CloudSyncState.LOCAL_ONLY ||
                existing.cloudState == CloudSyncState.ERROR
            )
    ) {
        return true
    }
    return remoteCloudIdentityChanged(existing, remote)
}

internal fun remoteCloudIdentityChanged(
    existing: SyncMetadataEntity,
    remote: RemoteDriveFile,
): Boolean {
    if (!existing.oneDriveItemId.isNullOrBlank() && existing.oneDriveItemId != remote.itemId) {
        return true
    }
    if (remote.quickXorHash != null && existing.remoteQuickXorHash != remote.quickXorHash) {
        return true
    }
    if (remote.sizeBytes != null && remote.sizeBytes > 0L && existing.remoteSizeBytes != remote.sizeBytes) {
        return true
    }
    if (remote.modifiedAtEpoch != null &&
        remote.modifiedAtEpoch > 0L &&
        existing.remoteModifiedAt != remote.modifiedAtEpoch
    ) {
        return true
    }
    return existing.displayName != remote.name
}
