package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.SyncPaths
import java.io.File

object GalleryMapper {
    private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")

    fun isVideoFileName(displayName: String): Boolean {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

    fun toDisplayItem(
        entity: SyncMetadataEntity,
        mediaCacheRepository: MediaCacheRepository,
    ): GalleryDisplayItem {
        val kind = if (isVideoFileName(entity.displayName)) MediaKind.VIDEO else MediaKind.IMAGE
        val localUri = entity.localContentUri?.let { Uri.parse(it) }
        val cached = entity.cachedFilePath?.let { path -> File(path) }
        val placeholderThumb = mediaCacheRepository.thumbnailIfCached(entity.canonicalPath)
        val sort = entity.capturedAtSeconds?.takeIf { it > 0L }
            ?: entity.remoteModifiedAt?.takeIf { it > 0L }
            ?: entity.localModifiedAt?.takeIf { it > 0L }
            ?: 0L
        return GalleryDisplayItem(
            canonicalPath = entity.canonicalPath,
            displayName = entity.displayName,
            albumName = resolveDisplayAlbumName(entity),
            kind = kind,
            cloudState = entity.cloudState,
            localMediaId = entity.localMediaId,
            oneDriveItemId = entity.oneDriveItemId,
            pendingDeleteTarget = entity.pendingDeleteTarget,
            localUri = localUri,
            cachedOriginal = cached,
            cachedPlaceholderThumb = placeholderThumb,
            sortTimestamp = sort,
            locationLabel = entity.locationLabel,
            latitude = entity.latitude,
            longitude = entity.longitude,
            videoDurationMs = entity.videoDurationMs,
            localReleasedForSpace = entity.localReleasedForSpace,
        )
    }

    /** Álbum virtual do usuário; senão pasta no caminho canônico (DCIM/Camera, não só "Camera"). */
    private fun resolveDisplayAlbumName(entity: SyncMetadataEntity): String {
        entity.userAlbumName?.takeIf { it.isNotBlank() }?.let { return it }
        val fromCanonical = SyncPaths.albumFromCanonical(entity.canonicalPath)
        if (fromCanonical != "Raiz") return fromCanonical
        return entity.albumName.ifBlank { "Raiz" }
    }
}
