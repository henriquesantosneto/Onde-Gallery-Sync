package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.sync.SyncPaths
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import java.io.File

object GalleryMapper {
    private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "tif", "tiff", "dng", "raw",
    )
    private val audioExtensions = setOf(
        "opus", "ogg", "oga", "mp3", "m4a", "aac", "wav", "flac", "amr", "3ga", "aiff", "aif", "wma",
    )

    fun isVideoFileName(displayName: String): Boolean {
        return fileExtension(displayName) in videoExtensions
    }

    fun isAudioFileName(displayName: String): Boolean {
        return fileExtension(displayName) in audioExtensions
    }

    /** Tipos para os quais faz sentido baixar/gerar miniatura (foto ou vídeo). */
    fun supportsGeneratedThumbnail(displayName: String): Boolean {
        val ext = fileExtension(displayName)
        if (ext in imageExtensions || ext in videoExtensions) return true
        if (ext in audioExtensions) return false
        // Extensão desconhecida: tenta só se parecer imagem/vídeo pelo nome
        return ext.isNotBlank() && ext !in documentLikeExtensions
    }

    private val documentLikeExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "apk",
    )

    private fun fileExtension(displayName: String): String =
        displayName.substringAfterLast('.', "").lowercase()

    fun toDisplayItem(
        entity: SyncMetadataEntity,
        mediaCacheRepository: MediaCacheRepository,
    ): GalleryDisplayItem {
        val kind = if (isVideoFileName(entity.displayName)) MediaKind.VIDEO else MediaKind.IMAGE
        val localUri = entity.localContentUri?.let { Uri.parse(it) }
        val cached = entity.cachedFilePath?.let { path -> File(path) }
            ?.takeIf { it.isFile && it.length() > 0L }
        val thumbCachedFast = mediaCacheRepository.isThumbnailCachedFast(
            entity.canonicalPath,
            entity.oneDriveItemId,
        )
        val placeholderThumb = if (thumbCachedFast) {
            mediaCacheRepository.displayableThumbnailIfCached(
                entity.canonicalPath,
                entity.oneDriveItemId,
            )
        } else {
            null
        }
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
            showInBrowseGrid = computeShowInBrowseGrid(
                localUri = localUri,
                cachedOriginal = cached,
                localMediaId = entity.localMediaId,
                localReleasedForSpace = entity.localReleasedForSpace,
                oneDriveItemId = entity.oneDriveItemId,
                cloudState = entity.cloudState,
                hasThumb = placeholderThumb != null || thumbCachedFast,
            ),
        )
    }

    private fun computeShowInBrowseGrid(
        localUri: Uri?,
        cachedOriginal: File?,
        localMediaId: Long?,
        localReleasedForSpace: Boolean,
        oneDriveItemId: String?,
        cloudState: CloudSyncState,
        hasThumb: Boolean,
    ): Boolean {
        if (localUri != null) return true
        if (cachedOriginal != null) return true
        if (localMediaId != null && !localReleasedForSpace) return true
        if (!oneDriveItemId.isNullOrBlank()) return true
        if (hasThumb) return true
        return cloudState != CloudSyncState.LOCAL_ONLY
    }

    private fun resolveDisplayAlbumName(entity: SyncMetadataEntity): String {
        return GalleryAlbumKeys.forEntity(entity)
    }

    /** MediaStore antes do sync indexar a linha no Room. */
    fun toDisplayItemFromLocal(
        item: LocalMediaItem,
        mediaCacheRepository: MediaCacheRepository,
    ): GalleryDisplayItem {
        val canonical = SyncPaths.canonicalFromLocal(item)
        val kind = if (isVideoFileName(item.displayName)) MediaKind.VIDEO else MediaKind.IMAGE
        val placeholderThumb = mediaCacheRepository.displayableThumbnailIfCached(canonical, null)
        val sort = item.dateTakenSeconds?.takeIf { it > 0L }
            ?: item.dateAddedSeconds.takeIf { it > 0L }
            ?: item.dateModifiedSeconds.takeIf { it > 0L }
            ?: 0L
        return GalleryDisplayItem(
            canonicalPath = canonical,
            displayName = item.displayName,
            albumName = SyncPaths.folderAlbumName(
                canonicalPath = canonical,
                relativePath = item.relativePath,
                bucketDisplayName = item.bucketDisplayName,
            ),
            kind = kind,
            cloudState = CloudSyncState.LOCAL_ONLY,
            localMediaId = item.id,
            oneDriveItemId = null,
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = item.uri,
            cachedOriginal = null,
            cachedPlaceholderThumb = placeholderThumb,
            sortTimestamp = sort,
            locationLabel = null,
            latitude = item.latitude,
            longitude = item.longitude,
            videoDurationMs = item.durationMs,
            localReleasedForSpace = false,
            showInBrowseGrid = true,
        )
    }
}
