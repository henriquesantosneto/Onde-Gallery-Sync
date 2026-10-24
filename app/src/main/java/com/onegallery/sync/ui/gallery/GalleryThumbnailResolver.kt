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
        style: GalleryThumbnailStyle = GalleryThumbnailStyle.Grid,
    ): Any? {
        val isVideo = item.kind == MediaKind.VIDEO
        val filmstripVideo = isVideo && style == GalleryThumbnailStyle.Filmstrip
        if (!filmstripVideo) {
            item.localUri?.let { uri -> return uri }
        }
        cachedDisplayableThumb(item, mediaCacheRepository)?.let { return it }
        item.cachedPlaceholderThumb?.let { thumb ->
            if (mediaCacheRepository.isDisplayableGalleryThumbFile(thumb) &&
                (!isVideo || VideoThumbnailHelper.isGalleryVideoThumbnailFile(thumb))
            ) {
                return thumb
            }
        }
        cloudVideoCoilKey(item, forFilmstrip = filmstripVideo)?.let { return it }
        if (filmstripVideo) {
            return null
        }
        if (isVideo) return null
        item.cachedOriginal?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
        return null
    }

    private fun cachedDisplayableThumb(
        item: GalleryDisplayItem,
        mediaCacheRepository: MediaCacheRepository,
    ): Any? {
        return mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId)
            ?.let { cached ->
                if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isGalleryVideoThumbnailFile(cached)) {
                    mediaCacheRepository.invalidateCommittedThumbnail(item.canonicalPath, item.oneDriveItemId)
                    null
                } else {
                    cached
                }
            }
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
        if (item.kind == MediaKind.VIDEO && displayData is android.net.Uri) return true
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
        style: GalleryThumbnailStyle = GalleryThumbnailStyle.Grid,
        diskCacheOnly: Boolean = false,
    ): Any? {
        val isVideo = item.kind == MediaKind.VIDEO
        val preferCloud = item.cloudState == CloudSyncState.CLOUD_ONLY ||
            item.cloudState == CloudSyncState.PENDING_DOWNLOAD
        val filmstripVideo = isVideo && style == GalleryThumbnailStyle.Filmstrip
        cachedDisplayableThumb(item, mediaCacheRepository)?.let { return it }
        if (diskCacheOnly) return null
        if (filmstripVideo && item.localUri != null) {
            GalleryThumbnailModels.resolveGridModel(context, item, sizePx)?.let { return it }
        }
        if (isVideo && thumbnailCache != null) {
            if (item.localUri != null) {
                thumbnailCache.ensureThumbnailCached(
                    item.canonicalPath,
                    preferCloudFirst = false,
                    localSourcesOnly = true,
                    cloudVideoAggressive = filmstripVideo,
                ).getOrNull()
                    ?.takeIf { VideoThumbnailHelper.isGalleryVideoThumbnailFile(it) }
                    ?.let { return it }
            }
            if (filmstripVideo || preferCloud) {
                thumbnailCache.ensureThumbnailCached(
                    item.canonicalPath,
                    preferCloudFirst = item.localUri != null && preferCloud,
                    cloudSourcesOnly = !item.oneDriveItemId.isNullOrBlank() && item.localUri == null,
                    cloudVideoAggressive = true,
                    lightweightPrefetch = false,
                ).getOrNull()
                    ?.takeIf { VideoThumbnailHelper.isGalleryVideoThumbnailFile(it) }
                    ?.let { return it }
            }
        }
        if (item.localUri != null && !filmstripVideo) {
            GalleryThumbnailModels.resolveGridModel(context, item, sizePx)?.let { return it }
            if (isVideo) {
                return item.localUri
            }
        }
        if (filmstripVideo) {
            cloudVideoCoilKey(item, forFilmstrip = true)?.let { return it }
            return null
        }
        thumbnailCache?.ensureThumbnailCached(
            item.canonicalPath,
            preferCloudFirst = preferCloud,
            cloudSourcesOnly = isVideo && preferCloud && !item.oneDriveItemId.isNullOrBlank(),
            cloudVideoAggressive = isVideo && (preferCloud || filmstripVideo),
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

    fun cloudVideoCoilKey(
        item: GalleryDisplayItem,
        forFilmstrip: Boolean = false,
    ): OneDriveCloudThumbnailKey? {
        if (item.kind != MediaKind.VIDEO) return null
        val itemId = item.oneDriveItemId?.takeIf { it.isNotBlank() } ?: return null
        if (!forFilmstrip &&
            item.localUri != null &&
            item.cloudState != CloudSyncState.CLOUD_ONLY &&
            item.cloudState != CloudSyncState.PENDING_DOWNLOAD
        ) {
            return null
        }
        return OneDriveCloudThumbnailKey(item.canonicalPath, itemId)
    }
}
