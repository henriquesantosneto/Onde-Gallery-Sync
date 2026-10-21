package com.onegallery.sync.data.cloud

import com.onegallery.sync.data.onedrive.RemoteDriveFile

data class RemoteCloudFile(
    val remoteItemId: String,
    val name: String,
    val canonicalPath: String,
    val modifiedAtEpoch: Long?,
    val sizeBytes: Long?,
    val contentHash: String? = null,
    val hashKind: RemoteContentHashKind = RemoteContentHashKind.UNKNOWN,
)

data class CloudRemoteListing(
    val files: List<RemoteCloudFile>,
    val incremental: Boolean,
    val deltaChangeCount: Int = 0,
    val unresolvedDeltaSkips: Int = 0,
    val supplementalFromRecursive: Int = 0,
)

fun RemoteDriveFile.toRemoteCloudFile(): RemoteCloudFile = RemoteCloudFile(
    remoteItemId = itemId,
    name = name,
    canonicalPath = canonicalPath,
    modifiedAtEpoch = modifiedAtEpoch,
    sizeBytes = sizeBytes,
    contentHash = quickXorHash,
    hashKind = if (quickXorHash != null) RemoteContentHashKind.QUICK_XOR else RemoteContentHashKind.UNKNOWN,
)

fun RemoteCloudFile.toRemoteDriveFile(): RemoteDriveFile = RemoteDriveFile(
    itemId = remoteItemId,
    name = name,
    canonicalPath = canonicalPath,
    modifiedAtEpoch = modifiedAtEpoch,
    sizeBytes = sizeBytes,
    quickXorHash = if (hashKind == RemoteContentHashKind.QUICK_XOR) contentHash else null,
)

fun CloudRemoteListing.toRemoteDriveListing() = com.onegallery.sync.data.onedrive.RemoteDriveListing(
    files = files.map { it.toRemoteDriveFile() },
    incremental = incremental,
    deltaChangeCount = deltaChangeCount,
    unresolvedDeltaSkips = unresolvedDeltaSkips,
    supplementalFromRecursive = supplementalFromRecursive,
)
