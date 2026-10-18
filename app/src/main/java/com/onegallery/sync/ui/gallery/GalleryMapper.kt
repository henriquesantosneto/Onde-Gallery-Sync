package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import java.io.File

object GalleryMapper {
    private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")

    fun toDisplayItem(entity: SyncMetadataEntity): GalleryDisplayItem {
        val ext = entity.displayName.substringAfterLast('.', "").lowercase()
        val kind = if (ext in videoExtensions) MediaKind.VIDEO else MediaKind.IMAGE
        val localUri = entity.localContentUri?.let { Uri.parse(it) }
        val cached = entity.cachedFilePath?.let { File(it) }?.takeIf { it.exists() }
        val sort = maxOf(entity.localModifiedAt ?: 0L, entity.remoteModifiedAt ?: 0L)
        return GalleryDisplayItem(
            canonicalPath = entity.canonicalPath,
            displayName = entity.displayName,
            albumName = entity.albumName,
            kind = kind,
            cloudState = entity.cloudState,
            localMediaId = entity.localMediaId,
            oneDriveItemId = entity.oneDriveItemId,
            pendingDeleteTarget = entity.pendingDeleteTarget,
            localUri = localUri,
            cachedOriginal = cached,
            sortTimestamp = sort,
        )
    }
}
