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
/** Capa de álbum: inclui vídeo local no aparelho mesmo sync/OneDrive (grade continua mais restrita). */
fun GalleryDisplayItem.isEligibleForAlbumCover(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (hasCachedThumbnailFile(this, mediaCacheRepository)) return true
    if (localReleasedForSpace) return false
    if (hasLocalVideoCoverOnDevice()) return true
    return hasBrowsableThumbnail(mediaCacheRepository)
}

fun resolveAlbumCoverItem(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): GalleryDisplayItem? {
    if (items.isEmpty()) return null
    val eligible = items.filter { it.isEligibleForAlbumCover(mediaCacheRepository) }
    if (eligible.isEmpty()) return null
    val preferred = eligible.filter { isStrongCoverCandidate(it, mediaCacheRepository) }
    val pool = if (preferred.isNotEmpty()) {
        preferred
    } else {
        eligible.filter {
            it.imageCountsAsLocalBrowseThumb() ||
                it.videoCountsAsLocalBrowseThumb() ||
                it.hasLocalVideoCoverOnDevice()
        }
    }
    if (pool.isEmpty()) return null
    return pool.maxWithOrNull(albumCoverComparator(mediaCacheRepository))
}

/** Miniatura em disco ou vídeo local (frame via MediaStore na UI). */
private fun isStrongCoverCandidate(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (hasCachedThumbnailFile(item, mediaCacheRepository)) return true
    if (item.hasLocalVideoCoverOnDevice()) return true
    return item.videoCountsAsLocalBrowseThumb() || item.imageCountsAsLocalBrowseThumb()
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
    if (item.kind == MediaKind.VIDEO) {
        val resolved = mediaCacheRepository.galleryThumbnailResolved(
            item.canonicalPath,
            item.oneDriveItemId,
            requireVideoAcceptable = true,
        )
        if (resolved != null) {
            if (VideoThumbnailHelper.isDisplayableVideoThumbnailFile(resolved)) {
                return true
            }
            mediaCacheRepository.invalidateCommittedThumbnail(
                item.canonicalPath,
                item.oneDriveItemId,
            )
        }
        val stub = item.cachedPlaceholderThumb
        if (stub != null && VideoThumbnailHelper.isDisplayableVideoThumbnailFile(stub)) {
            return true
        }
        return false
    }
    val cached = mediaCacheRepository.displayableThumbnailIfCached(
        item.canonicalPath,
        item.oneDriveItemId,
    )
    if (cached != null) return true
    val stub = item.cachedPlaceholderThumb
    return stub != null &&
        !mediaCacheRepository.isSyncFailurePlaceholderThumbnail(stub) &&
        mediaCacheRepository.isDisplayableGalleryThumbFile(stub)
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
    if (mediaCacheRepository != null &&
        !item.isEligibleForAlbumCover(mediaCacheRepository)
    ) {
        return null
    }
    val cachedThumb = mediaCacheRepository?.displayableThumbnailIfCached(
        item.canonicalPath,
        item.oneDriveItemId,
    ) ?: item.cachedPlaceholderThumb?.takeIf { thumb ->
        mediaCacheRepository?.isDisplayableGalleryThumbFile(thumb) == true
    }
    if (item.kind == MediaKind.VIDEO) {
        cachedThumb?.takeIf { VideoThumbnailHelper.isDisplayableVideoThumbnailFile(it) }
            ?.let { return it }
        if (item.hasLocalVideoCoverOnDevice() || item.videoCountsAsLocalBrowseThumb()) {
            item.localUri?.let { return it }
        }
        return null
    }
    if (item.imageCountsAsLocalBrowseThumb()) {
        cachedThumb?.let { return it }
        item.localUri?.let { return it }
        item.cachedOriginal?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
    }
    return null
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

/** Muda quando a capa escolhida ou a miniatura em disco dela muda (fotos locais novas). */
fun albumCoverSelectionSignature(
    items: List<GalleryDisplayItem>,
    mediaCacheRepository: MediaCacheRepository,
): Long {
    val cover = resolveAlbumCoverItem(items, mediaCacheRepository) ?: return 0L
    val thumbMtime = mediaCacheRepository.thumbnailIfCached(
        cover.canonicalPath,
        cover.oneDriveItemId,
    )?.lastModified()
        ?: cover.cachedPlaceholderThumb?.takeIf { it.isFile }?.lastModified()
        ?: 0L
    var hash = cover.canonicalPath.hashCode().toLong()
    hash = hash * 31L + cover.sortTimestamp
    hash = hash * 31L + thumbMtime
    hash = hash * 31L + cover.localMediaId.hashCode()
    hash = hash * 31L + (cover.localUri?.hashCode()?.toLong() ?: 0L)
    hash = hash * 31L + (cover.cachedOriginal?.takeIf { it.isFile }?.lastModified() ?: 0L)
    return hash
}
