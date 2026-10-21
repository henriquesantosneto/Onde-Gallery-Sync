package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.SyncPaths
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
        val placeholderThumb = if (
            mediaCacheRepository.isThumbnailCachedFast(entity.canonicalPath, entity.oneDriveItemId)
        ) {
            mediaCacheRepository.thumbFile(entity.canonicalPath).takeIf { it.isFile && it.length() > 0L }
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
        )
    }

    private fun resolveDisplayAlbumName(entity: SyncMetadataEntity): String {
        return GalleryAlbumKeys.forEntity(entity)
    }
}
