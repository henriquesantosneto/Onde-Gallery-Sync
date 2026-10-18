package com.onegallery.sync.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.onegallery.sync.sync.CloudSyncState

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val localMediaId: Long,
    val localContentUri: String,
    val relativePath: String?,
    val oneDriveItemId: String?,
    val oneDrivePath: String?,
    val localModifiedAt: Long,
    val remoteModifiedAt: Long?,
    val cloudState: CloudSyncState,
    val lastError: String?,
)
