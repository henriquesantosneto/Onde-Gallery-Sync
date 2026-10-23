package com.onegallery.sync.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaDeleteRequestRouter
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.onedrive.CloudThumbnailStaging
import com.onegallery.sync.data.onedrive.CloudVideoThumbnailLoader
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.ui.gallery.GalleryMapper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cache de miniatura por item: local, Graph e placeholders. */
internal class GalleryThumbnailService(
    private val appContext: Context,
    private val syncMetadataDao: SyncMetadataDao,
    private val mediaCacheRepository: MediaCacheRepository,
    private val oneDriveRepository: OneDriveRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val syncPreferences: SyncPreferences,
    private val networkMonitor: NetworkMonitor,
    private val mediaDeleteRequestRouter: MediaDeleteRequestRouter,
    private val localIndexPassItems: () -> Map<Long, LocalMediaItem>,
) {
    private val thumbnailMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun ensureThumbnailCached(
        canonicalPath: String,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
        preferCloudFirst: Boolean = false,
        lightweightPrefetch: Boolean = false,
        localSourcesOnly: Boolean = false,
        cloudSourcesOnly: Boolean = false,
        cloudVideoAggressive: Boolean = false,
    ): Result<File?> {
        val mutex = thumbnailMutexes.getOrPut(canonicalPath) { Mutex() }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureThumbnailCachedOnIo(
                    canonicalPath,
                    onProgress,
                    preferCloudFirst,
                    lightweightPrefetch,
                    localSourcesOnly,
                    cloudSourcesOnly,
                    cloudVideoAggressive,
                )
            }
        }
    }

    fun canBuildThumbnail(row: SyncMetadataEntity): Boolean {
        if (!GalleryMapper.supportsGeneratedThumbnail(row.displayName)) return false
        if (row.oneDriveItemId != null) return true
        if (row.localMediaId != null) return true
        if (!row.localContentUri.isNullOrBlank()) return true
        if (!row.cachedFilePath.isNullOrBlank()) return true
        return false
    }

    fun hasLocalThumbnailSource(row: SyncMetadataEntity): Boolean {
        return row.localMediaId != null ||
            !row.localContentUri.isNullOrBlank() ||
            !row.cachedFilePath.isNullOrBlank()
    }

    fun rowHasGalleryThumbnail(row: SyncMetadataEntity): Boolean {
        val isVideo = GalleryMapper.isVideoFileName(row.displayName)
        if (
            mediaCacheRepository.galleryThumbnailResolved(
                row.canonicalPath,
                row.oneDriveItemId,
                requireVideoAcceptable = isVideo,
            ) != null
        ) {
            return true
        }
        if (!isVideo && hasLocalThumbnailSource(row) && localContentUri(row) != null) {
            return true
        }
        return false
    }

    fun needsThumbnailPrefetch(row: SyncMetadataEntity): Boolean {
        if (!canBuildThumbnail(row)) return false
        return !rowHasGalleryThumbnail(row)
    }

    fun localContentUri(row: SyncMetadataEntity): Uri? {
        row.localContentUri?.let { parsed ->
            val uri = Uri.parse(parsed)
            if (mediaDeleteRequestRouter.uriExists(appContext, uri)) return uri
        }
        row.localMediaId?.let { id ->
            val kind = if (GalleryMapper.isVideoFileName(row.displayName)) {
                com.onegallery.sync.data.local.MediaKind.VIDEO
            } else {
                com.onegallery.sync.data.local.MediaKind.IMAGE
            }
            localMediaRepository.contentUriForMediaId(id, kind)?.let { return it }
        }
        return null
    }

    fun persistMinimalPlaceholderThumbnail(row: SyncMetadataEntity): File? {
        if (rowHasGalleryThumbnail(row)) {
            return mediaCacheRepository.galleryThumbnailResolved(
                row.canonicalPath,
                row.oneDriveItemId,
                requireVideoAcceptable = GalleryMapper.isVideoFileName(row.displayName),
            )
        }
        mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId)?.let {
            mediaCacheRepository.invalidateCommittedThumbnail(row.canonicalPath, row.oneDriveItemId)
        }
        val staging = mediaCacheRepository.thumbStagingFile(row.canonicalPath)
        staging.delete()
        return runCatching {
            val edge = MediaCacheRepository.SYNC_FAILURE_PLACEHOLDER_EDGE_PX
            val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.RGB_565)
            try {
                bitmap.eraseColor(MediaCacheRepository.SYNC_STUB_PLACEHOLDER_COLOR)
                staging.parentFile?.mkdirs()
                staging.outputStream().use { out ->
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        CloudThumbnailStaging.THUMB_CACHE_JPEG_QUALITY,
                        out,
                    )
                }
            } finally {
                bitmap.recycle()
            }
            if (staging.length() <= 0L) return null
            mediaCacheRepository.commitThumbnail(staging, row.canonicalPath, row.oneDriveItemId)
        }.getOrNull()
    }

    private suspend fun ensureThumbnailCachedOnIo(
        canonicalPath: String,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
        preferCloudFirst: Boolean = false,
        lightweightPrefetch: Boolean = false,
        localSourcesOnly: Boolean = false,
        cloudSourcesOnly: Boolean = false,
        cloudVideoAggressive: Boolean = false,
    ): Result<File?> {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath) ?: return Result.success(null)
        if (!GalleryMapper.supportsGeneratedThumbnail(row.displayName)) {
            persistMinimalPlaceholderThumbnail(row)?.let { return Result.success(it) }
            return Result.success(null)
        }
        val isVideo = GalleryMapper.isVideoFileName(row.displayName)
        val hasLocalSource = localContentUri(row) != null
        if (hasLocalSource) {
            mediaCacheRepository.committedThumbnailIncludingGiveUp(
                row.canonicalPath,
                row.oneDriveItemId,
            )?.let { cached ->
                if (mediaCacheRepository.isSyncFailurePlaceholderThumbnail(cached)) {
                    mediaCacheRepository.invalidateCommittedThumbnail(row.canonicalPath, row.oneDriveItemId)
                }
            }
        }
        val syncedOnGraph = !row.oneDriveItemId.isNullOrBlank() &&
            syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH
        val cloudEligible = syncedOnGraph && networkMonitor.isOnline()
        val cloudFirst = preferCloudFirst || (cloudEligible && !hasLocalSource)

        fun existingCachedThumb(): File? =
            mediaCacheRepository.thumbnailIfCached(canonicalPath, row.oneDriveItemId)
                ?.takeIf { cached ->
                    !isVideo || VideoThumbnailHelper.isGalleryVideoThumbnailFile(cached)
                }

        if (!syncedOnGraph && !cloudFirst) {
            if (rowHasGalleryThumbnail(row)) {
                syncPreferences.clearThumbnailFailure(canonicalPath)
                return Result.success(
                    mediaCacheRepository.galleryThumbnailResolved(
                        canonicalPath,
                        row.oneDriveItemId,
                        requireVideoAcceptable = isVideo,
                    ),
                )
            }
            mediaCacheRepository.thumbnailIfCached(canonicalPath, row.oneDriveItemId)?.let {
                mediaCacheRepository.invalidateCommittedThumbnail(canonicalPath, row.oneDriveItemId)
            }
        } else if (syncedOnGraph || cloudFirst) {
            if (rowHasGalleryThumbnail(row)) {
                syncPreferences.clearThumbnailFailure(canonicalPath)
                return Result.success(
                    mediaCacheRepository.galleryThumbnailResolved(
                        canonicalPath,
                        row.oneDriveItemId,
                        requireVideoAcceptable = isVideo,
                    ),
                )
            }
            mediaCacheRepository.thumbnailIfCached(canonicalPath, row.oneDriveItemId)?.let {
                mediaCacheRepository.invalidateCommittedThumbnail(canonicalPath, row.oneDriveItemId)
            }
        }

        val staging = mediaCacheRepository.thumbStagingFile(canonicalPath)
        staging.delete()
        onProgress?.invoke(0L, null)

        fun commitIfReady(allowGraphVideoPoster: Boolean = false): File? {
            if (isVideo) {
                return CloudThumbnailStaging.commitGalleryVideoThumbnailIfValid(
                    staging,
                    mediaCacheRepository,
                    canonicalPath,
                    row.oneDriveItemId,
                    fromGraphPoster = allowGraphVideoPoster,
                )
            }
            if (!mediaCacheRepository.isDisplayableGalleryThumbFile(staging)) {
                if (!CloudThumbnailStaging.reencodeFileToDisplayableThumbnail(
                        staging,
                        staging,
                        mediaCacheRepository,
                    )
                ) {
                    staging.delete()
                    return null
                }
            }
            return mediaCacheRepository.commitThumbnail(staging, canonicalPath, row.oneDriveItemId)
        }

        fun successFromStaging(allowGraphVideoPoster: Boolean = false): Result<File?> {
            commitIfReady(allowGraphVideoPoster)?.let { file ->
                syncPreferences.clearThumbnailFailure(canonicalPath)
                onProgress?.invoke(file.length(), file.length())
                return Result.success(file)
            }
            staging.delete()
            return Result.success(null)
        }

        suspend fun tryCloudSource(): Result<File?>? {
            val itemId = row.oneDriveItemId ?: return null
            if (!cloudEligible) return null
            staging.delete()
            val outcome = try {
                withTimeout(
                    when {
                        isVideo && (cloudVideoAggressive || !lightweightPrefetch) ->
                            CLOUD_VIDEO_ON_DEMAND_FETCH_TIMEOUT_MS
                        isVideo -> CLOUD_VIDEO_THUMB_FETCH_TIMEOUT_MS
                        else -> CLOUD_THUMB_FETCH_TIMEOUT_MS
                    },
                ) {
                    if (isVideo) {
                        CloudVideoThumbnailLoader.loadIntoFile(
                            oneDriveRepository,
                            mediaCacheRepository,
                            itemId,
                            staging,
                            row.remoteSizeBytes,
                            cloudVideoAggressive || !lightweightPrefetch,
                            onProgress,
                        )
                    } else if (fetchCloudImageThumbnail(itemId, staging, row.remoteSizeBytes, onProgress)) {
                        CloudVideoThumbnailLoader.Outcome.GRAPH_POSTER
                    } else {
                        CloudVideoThumbnailLoader.Outcome.NONE
                    }
                }
            } catch (_: TimeoutCancellationException) {
                CloudVideoThumbnailLoader.Outcome.NONE
            }
            if (outcome == CloudVideoThumbnailLoader.Outcome.NONE) {
                staging.delete()
                return null
            }
            val fromGraphPoster = outcome == CloudVideoThumbnailLoader.Outcome.GRAPH_POSTER
            return successFromStaging(fromGraphPoster).takeIf { it.getOrNull() != null }
        }

        fun tryLocalSources(): Result<File?>? {
            localContentUri(row)?.let { uri ->
                val ok = if (isVideo) {
                    VideoThumbnailHelper.captureToFile(
                        appContext,
                        uri,
                        staging,
                        localMediaId = row.localMediaId,
                        mediaStoreOnly = lightweightPrefetch && !isVideo,
                    )
                } else {
                    cacheImageThumbnailFromUri(uri, staging)
                }
                if (ok) {
                    successFromStaging().getOrNull()?.let { return Result.success(it) }
                }
            }

            if (isVideo && row.localMediaId != null) {
                val item = localIndexPassItems()[row.localMediaId]
                    ?: localMediaRepository.findById(row.localMediaId)
                if (item != null) {
                    val temp = localMediaRepository.materializeToTempFile(item)
                    if (temp != null) {
                        try {
                            if (VideoThumbnailHelper.captureToFile(temp, staging)) {
                                successFromStaging().getOrNull()?.let { return Result.success(it) }
                            }
                        } finally {
                            temp.delete()
                        }
                    }
                }
            }

            row.cachedFilePath?.let { path ->
                val cached = File(path)
                if (cached.isFile && cached.length() > 0L) {
                    val ok = if (isVideo) {
                        VideoThumbnailHelper.captureToFile(cached, staging)
                    } else {
                        cacheImageThumbnailFromUri(Uri.fromFile(cached), staging)
                    }
                    if (ok) {
                        successFromStaging().getOrNull()?.let { return Result.success(it) }
                    }
                }
            }
            staging.delete()
            return null
        }

        when {
            cloudSourcesOnly -> tryCloudSource()?.let { return it }
            localSourcesOnly -> tryLocalSources()?.let { return it }
            syncedOnGraph -> {
                if (isVideo && hasLocalSource && !cloudSourcesOnly) {
                    tryLocalSources()?.let { return it }
                }
                if (cloudEligible && !localSourcesOnly) {
                    tryCloudSource()?.let { return it }
                }
                if (hasLocalSource && !cloudSourcesOnly) {
                    tryLocalSources()?.let { return it }
                }
            }
            cloudFirst -> {
                tryCloudSource()?.let { return it }
                tryLocalSources()?.let { return it }
            }
            else -> {
                tryLocalSources()?.let { return it }
                tryCloudSource()?.let { return it }
            }
        }

        staging.delete()
        existingCachedThumb()?.let { cached ->
            syncPreferences.clearThumbnailFailure(canonicalPath)
            return Result.success(cached)
        }

        val maxThumbFailures = if (isVideo) THUMB_MAX_FAILURE_ATTEMPTS_VIDEO else THUMB_MAX_FAILURE_ATTEMPTS
        if (syncPreferences.incrementThumbnailFailure(canonicalPath) >= maxThumbFailures) {
            if (!isVideo && hasLocalSource) {
                syncPreferences.clearThumbnailFailure(canonicalPath)
                return Result.success(null)
            }
            persistMinimalPlaceholderThumbnail(row)?.let { placeholder ->
                syncPreferences.clearThumbnailFailure(canonicalPath)
                return Result.success(placeholder)
            }
        }
        return Result.success(null)
    }

    private suspend fun fetchCloudImageThumbnail(
        itemId: String,
        staging: File,
        remoteSizeBytes: Long?,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)?,
    ): Boolean {
        var thumbReady = false
        oneDriveRepository.downloadThumbnailToFile(itemId, staging, onProgress).onSuccess {
            thumbReady = CloudThumbnailStaging.acceptDownloadedCloudThumbnail(
                staging,
                mediaCacheRepository,
                isVideo = false,
            )
            if (!thumbReady) staging.delete()
        }
        if (thumbReady) return true
        val partial = File(staging.absolutePath + ".partial")
        partial.delete()
        val remoteSize = remoteSizeBytes?.takeIf { it > 0L }
        oneDriveRepository.downloadImagePrefixForThumbnail(
            itemId,
            partial,
            maxBytes = 4L * 1024L * 1024L,
            onProgress = onProgress,
        ).onSuccess {
            if (partial.isFile && partial.length() > 0L) {
                thumbReady = cacheImageThumbnailFromUri(Uri.fromFile(partial), staging) ||
                    CloudThumbnailStaging.reencodeFileToDisplayableThumbnail(
                        partial,
                        staging,
                        mediaCacheRepository,
                    )
            }
        }
        if (!thumbReady) {
            val tail = File(staging.absolutePath + ".partial.imgtail")
            tail.delete()
            oneDriveRepository.downloadFileSuffixForThumbnail(
                itemId,
                tail,
                maxBytes = 2L * 1024L * 1024L,
                knownSizeBytes = remoteSize,
                onProgress = onProgress,
            ).onSuccess {
                if (tail.isFile && tail.length() > 0L) {
                    thumbReady = cacheImageThumbnailFromUri(Uri.fromFile(tail), staging) ||
                        CloudThumbnailStaging.reencodeFileToDisplayableThumbnail(
                            tail,
                            staging,
                            mediaCacheRepository,
                        )
                }
            }
            tail.delete()
        }
        partial.delete()
        return thumbReady
    }

    private fun cacheImageThumbnailFromUri(
        uri: Uri,
        target: File,
        maxSidePx: Int = CloudThumbnailStaging.THUMB_CACHE_MAX_SIDE_PX,
    ): Boolean {
        return runCatching {
            target.parentFile?.mkdirs()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { probe ->
                BitmapFactory.decodeStream(probe, null, bounds)
            } ?: return false
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val sample = CloudThumbnailStaging.thumbnailInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = appContext.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: return false
            try {
                target.outputStream().use { out ->
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        CloudThumbnailStaging.THUMB_CACHE_JPEG_QUALITY,
                        out,
                    )
                }
                target.length() > 0L
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
    }

    companion object {
        const val THUMB_MAX_FAILURE_ATTEMPTS = 5
        const val THUMB_MAX_FAILURE_ATTEMPTS_VIDEO = 12
        const val CLOUD_THUMB_FETCH_TIMEOUT_MS = 25_000L
        const val CLOUD_VIDEO_THUMB_FETCH_TIMEOUT_MS = 90_000L
        const val CLOUD_VIDEO_ON_DEMAND_FETCH_TIMEOUT_MS = 180_000L
    }
}
