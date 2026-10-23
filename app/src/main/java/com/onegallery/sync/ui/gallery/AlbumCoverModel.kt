package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File

/** Capa resolvida ao rolar a grade (modelo + item de origem, ex. vídeo). */
data class LiveAlbumCover(
    val displayModel: Any?,
    val sourceItem: GalleryDisplayItem?,
)

/**
 * Capa de álbum:
 * 1) mídia **mais recente** (`sortTimestamp`) que já tenha **arquivo de miniatura** no cache do app;
 * 2) se nenhuma, a mais recente exibível (URI local / original / thumb).
 */
fun resolveAlbumCoverItem(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): GalleryDisplayItem? {
    if (items.isEmpty()) return null
    val preferred = items.filter { isStrongCoverCandidate(it, mediaCacheRepository) }
    if (preferred.isNotEmpty()) {
        return preferred.maxWithOrNull(albumCoverComparator(mediaCacheRepository))
    }
    return items
        .asSequence()
        .filter { galleryItemCoverReady(it, mediaCacheRepository) }
        .maxWithOrNull(albumCoverComparator(mediaCacheRepository))
}

/** Miniatura em disco ou vídeo local (frame via MediaStore na UI). */
private fun isStrongCoverCandidate(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (hasCachedThumbnailFile(item, mediaCacheRepository)) return true
    return item.kind == MediaKind.VIDEO && item.localUri != null
}

private fun albumCoverComparator(mediaCacheRepository: MediaCacheRepository) =
    compareBy<GalleryDisplayItem>(
        { it.sortTimestamp },
        { item ->
            mediaCacheRepository.thumbnailIfCached(item.canonicalPath)?.lastModified()
                ?: item.cachedPlaceholderThumb?.takeIf { it.isFile }?.lastModified()
                ?: 0L
        },
    )

fun hasCachedThumbnailFile(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    val cached = mediaCacheRepository.displayableThumbnailIfCached(
        item.canonicalPath,
        item.oneDriveItemId,
    )
    if (cached != null) {
        if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isAcceptableThumbnailFile(cached)) {
            mediaCacheRepository.invalidateCommittedThumbnail(item.canonicalPath, item.oneDriveItemId)
        } else {
            return true
        }
    }
    val stub = item.cachedPlaceholderThumb
    if (stub != null && mediaCacheRepository.isDisplayableGalleryThumbFile(stub)) {
        if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isAcceptableThumbnailFile(stub)) {
            return false
        }
        return true
    }
    return false
}

fun resolveAlbumCoverModel(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): Any? {
    return resolveAlbumCoverItem(items, mediaCacheRepository)?.let {
        resolveGalleryCoverModel(it, mediaCacheRepository)
    }
}

fun galleryItemCoverReady(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    return resolveGalleryCoverModel(item, mediaCacheRepository) != null
}

fun resolveGalleryCoverModel(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository? = null,
): Any? {
    val cachedThumb = mediaCacheRepository?.displayableThumbnailIfCached(
        item.canonicalPath,
        item.oneDriveItemId,
    ) ?: item.cachedPlaceholderThumb?.takeIf { thumb ->
        mediaCacheRepository?.isDisplayableGalleryThumbFile(thumb) == true
    }
    if (item.kind == MediaKind.VIDEO) {
        cachedThumb?.takeIf { VideoThumbnailHelper.isAcceptableThumbnailFile(it) }?.let { return it }
        return item.thumbnailModel
    }
    item.localUri?.let { return it }
    item.cachedOriginal?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
    cachedThumb?.let { return it }
    return item.thumbnailModel
}

fun resolveLiveAlbumCover(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
    fallbackModel: Any? = null,
): LiveAlbumCover {
    val item = resolveAlbumCoverItem(items, mediaCacheRepository)
    val model = item?.let { resolveGalleryCoverModel(it, mediaCacheRepository) } ?: fallbackModel
    return LiveAlbumCover(displayModel = model, sourceItem = item)
}
