package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind

fun GalleryDisplayItem.isVisibleInGalleryBrowse(
    mediaCacheRepository: MediaCacheRepository,
): Boolean = appearsInOneDriveGalleryBrowse(mediaCacheRepository)

fun GalleryDisplayItem.isVisibleInGalleryBrowseWithThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean = appearsInOneDriveGalleryBrowse(mediaCacheRepository)

fun GalleryDisplayItem.hasBrowsableThumbnail(
    mediaCacheRepository: MediaCacheRepository,
): Boolean = appearsInOneDriveGalleryBrowse(mediaCacheRepository)

fun GalleryDisplayItem.imageCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.IMAGE) return false
    return mediaCountsAsLocalBrowseThumb()
}

fun GalleryDisplayItem.videoCountsAsLocalBrowseThumb(): Boolean {
    if (kind != MediaKind.VIDEO) return false
    return mediaCountsAsLocalBrowseThumb()
}
