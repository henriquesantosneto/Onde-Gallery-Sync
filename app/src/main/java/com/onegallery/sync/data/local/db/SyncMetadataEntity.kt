package com.onegallery.sync.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val canonicalPath: String,
    val displayName: String,
    val albumName: String,
    val localMediaId: Long?,
    val localContentUri: String?,
    val oneDriveItemId: String?,
    val localModifiedAt: Long?,
    val remoteModifiedAt: Long?,
    val cloudState: CloudSyncState,
    val cachedFilePath: String?,
    val pendingDeleteTarget: PendingDeleteTarget,
    val lastError: String?,
)
