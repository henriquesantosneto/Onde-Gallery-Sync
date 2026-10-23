package com.onegallery.sync.ui.gallery

import android.content.Context
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.GalleryThumbnailCache

object GalleryThumbnailResolver {

    fun initialDisplayData(
        item: GalleryDisplayItem,
        mediaCacheRepository: MediaCacheRepository,
    ): Any? {
        item.localUri?.let { uri ->
            return uri
        }
        mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId)
            ?.let { cached ->
                if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isGalleryVideoThumbnailFile(cached)) {
                    mediaCacheRepository.invalidateCommittedThumbnail(item.canonicalPath, item.oneDriveItemId)
                } else {
                    return cached
                }
            }
        val thumb = item.cachedPlaceholderThumb
        if (thumb != null && mediaCacheRepository.isDisplayableGalleryThumbFile(thumb)) {
            if (item.kind != MediaKind.VIDEO ||
                VideoThumbnailHelper.isGalleryVideoThumbnailFile(thumb)
            ) {
                return thumb
            }
        }
        cloudVideoCoilKey(item)?.let { return it }
        if (item.kind == MediaKind.VIDEO) {
            return null
        }
        item.cachedOriginal?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
        return null
    }

    fun isStaleDisplayData(
        item: GalleryDisplayItem,
        displayData: Any?,
        mediaCacheRepository: MediaCacheRepository,
    ): Boolean {
        val file = displayData as? java.io.File ?: return false
        if (mediaCacheRepository.isSyncFailurePlaceholderThumbnail(file)) {
            return item.localUri != null && item.kind != MediaKind.VIDEO
        }
        if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isGalleryVideoThumbnailFile(file)) {
            return true
        }
        return false
    }

    fun shouldShowResolvingSpinner(
        displayData: Any?,
        thumbnailCache: GalleryThumbnailCache?,
        item: GalleryDisplayItem,
    ): Boolean {
        if (displayData is OneDriveCloudThumbnailKey) return false
        if (displayData != null) return false
        if (thumbnailCache == null) return false
        return item.oneDriveItemId != null ||
            (item.kind == MediaKind.VIDEO && item.localUri != null)
    }

    suspend fun resolveForGrid(
        context: Context,
        item: GalleryDisplayItem,
        thumbnailCache: GalleryThumbnailCache?,
        mediaCacheRepository: MediaCacheRepository,
        sizePx: Int,
    ): Any? {
        val isVideo = item.kind == MediaKind.VIDEO
        val preferCloud = item.cloudState == CloudSyncState.CLOUD_ONLY ||
            item.cloudState == CloudSyncState.PENDING_DOWNLOAD
        mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId)
            ?.let { cached ->
                if (isVideo && !VideoThumbnailHelper.isGalleryVideoThumbnailFile(cached)) {
                    mediaCacheRepository.invalidateCommittedThumbnail(
                        item.canonicalPath,
                        item.oneDriveItemId,
                    )
                } else {
                    return cached
                }
            }
        if (isVideo && preferCloud && thumbnailCache != null) {
            thumbnailCache.ensureThumbnailCached(
                item.canonicalPath,
                preferCloudFirst = true,
                cloudSourcesOnly = !item.oneDriveItemId.isNullOrBlank(),
                cloudVideoAggressive = true,
            ).getOrNull()
                ?.takeIf { VideoThumbnailHelper.isGalleryVideoThumbnailFile(it) }
                ?.let { return it }
        }
        if (isVideo && item.localUri != null && thumbnailCache != null) {
            thumbnailCache.ensureThumbnailCached(
                item.canonicalPath,
                preferCloudFirst = false,
                localSourcesOnly = true,
            ).getOrNull()
                ?.takeIf { VideoThumbnailHelper.isGalleryVideoThumbnailFile(it) }
                ?.let { return it }
        }
        if (item.localUri != null) {
            GalleryThumbnailModels.resolveGridModel(context, item, sizePx)?.let { return it }
            if (isVideo) {
                return item.localUri
            }
        }
        thumbnailCache?.ensureThumbnailCached(
            item.canonicalPath,
            preferCloudFirst = preferCloud,
            cloudSourcesOnly = isVideo && preferCloud && !item.oneDriveItemId.isNullOrBlank(),
            cloudVideoAggressive = isVideo && preferCloud,
        )?.getOrNull()
            ?.takeIf {
                if (isVideo) {
                    VideoThumbnailHelper.isGalleryVideoThumbnailFile(it)
                } else {
                    mediaCacheRepository.isDisplayableGalleryThumbFile(it)
                }
            }
            ?.let { return it }
        return cloudVideoCoilKey(item) ?: if (isVideo) {
            null
        } else {
            item.thumbnailModel
        }
    }

    fun cloudVideoCoilKey(item: GalleryDisplayItem): OneDriveCloudThumbnailKey? {
        if (item.kind != MediaKind.VIDEO) return null
        val itemId = item.oneDriveItemId?.takeIf { it.isNotBlank() } ?: return null
        if (item.localUri != null &&
            item.cloudState != CloudSyncState.CLOUD_ONLY &&
            item.cloudState != CloudSyncState.PENDING_DOWNLOAD
        ) {
            return null
        }
        return OneDriveCloudThumbnailKey(item.canonicalPath, itemId)
    }
}
