package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File

internal fun GalleryDisplayItem.isCloudOnlyVideoThumb(): Boolean =
    localUri == null && !oneDriveItemId.isNullOrBlank() && kind == MediaKind.VIDEO

internal fun isCommittedGridVideoThumbFile(
    file: File,
    allowCloudPoster: Boolean,
): Boolean {
    if (VideoThumbnailHelper.isGalleryVideoThumbnailFile(file)) return true
    if (allowCloudPoster && VideoThumbnailHelper.isCloudPosterThumbnailFile(file)) return true
    return false
}

/** JPEG de vídeo na grade: frame local ou poster Graph (nuvem). */
internal fun GalleryDisplayItem.resolvedGridVideoThumbFile(
    mediaCache: MediaCacheRepository,
): File? {
    val allowCloudPoster = isCloudOnlyVideoThumb()
    fun accept(file: File?): File? =
        file?.takeIf { isCommittedGridVideoThumbFile(it, allowCloudPoster) }
    return accept(
        cachedPlaceholderThumb?.takeIf {
            VideoThumbnailHelper.isGalleryVideoThumbnailFile(it) ||
                (allowCloudPoster && VideoThumbnailHelper.isCloudPosterThumbnailFile(it))
        },
    ) ?: accept(
        mediaCache.galleryThumbnailResolved(
            canonicalPath,
            oneDriveItemId,
            requireVideoAcceptable = true,
            allowCloudVideoPoster = allowCloudPoster,
        ),
    )
}

internal fun isInvalidCommittedVideoThumb(
    file: File,
    allowCloudPoster: Boolean,
): Boolean {
    if (!VideoThumbnailHelper.isAcceptableThumbnailFile(file)) return true
    return !isCommittedGridVideoThumbFile(file, allowCloudPoster)
}
