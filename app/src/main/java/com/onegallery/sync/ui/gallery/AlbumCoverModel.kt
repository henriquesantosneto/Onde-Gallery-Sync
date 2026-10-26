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

/** Poster de vídeo no aparelho. A UI lê o MediaStore; o Coil não recebe o arquivo. */
data class DeviceVideoCover(
    val uri: android.net.Uri,
    val mediaId: Long?,
)

/**
 * Capa de álbum:
 * 1) mídia **mais recente** (`sortTimestamp`) que já tenha **arquivo de miniatura** no cache do app;
 * 2) se nenhuma, a mais recente exibível (URI local / original / thumb).
 */
/** Capa e pasta: só item que já tem miniatura gravada pelo sincronismo. */
fun GalleryDisplayItem.isEligibleForAlbumCover(
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (GalleryMapper.isAudioFileName(displayName)) return false
    if (isLocalPhotoOnDevice()) return true
    if (hasLocalVideoCoverOnDevice()) return true
    return hasCachedThumbnailFile(this, mediaCacheRepository)
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
            it.isLocalPhotoOnDevice() ||
                it.imageCountsAsLocalBrowseThumb() ||
                it.videoCountsAsLocalBrowseThumb() ||
                it.hasLocalVideoCoverOnDevice()
        }
    }
    if (pool.isEmpty()) return null
    val ranked = pool.sortedWith(albumCoverComparator(mediaCacheRepository)).asReversed()
    return ranked.firstOrNull { item ->
        resolveGalleryCoverModel(item, mediaCacheRepository) != null
    }
}

/** Miniatura em disco ou vídeo local (frame via MediaStore na UI). */
private fun isStrongCoverCandidate(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (hasCachedThumbnailFile(item, mediaCacheRepository)) return true
    if (item.hasLocalVideoCoverOnDevice()) return true
    if (item.isLocalPhotoOnDevice()) return true
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

/** Miniatura de capa: JPEG real. O cinza 96px do sync não entra. */
internal fun albumCoverThumbFile(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): File? {
    fun accept(file: File?): File? = file?.takeIf { albumCoverThumbFileAccept(it, mediaCacheRepository) }
    accept(mediaCacheRepository.thumbFile(item.canonicalPath))?.let { return it }
    val remoteId = item.oneDriveItemId
    if (!remoteId.isNullOrBlank()) {
        accept(mediaCacheRepository.thumbFileByRemoteItemId(remoteId))?.let { return it }
    }
    return accept(item.cachedPlaceholderThumb)
}

private fun albumCoverThumbFileAccept(
    file: File,
    mediaCacheRepository: MediaCacheRepository,
): Boolean {
    if (!file.isSafeGridImageFile()) return false
    return mediaCacheRepository.isDisplayableGalleryThumbFile(file)
}

fun cachedBrowseThumbFile(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): File? {
    if (item.kind == MediaKind.VIDEO) {
        return gridPaintFile(item, mediaCacheRepository)
    }
    val cached = mediaCacheRepository.displayableThumbnailIfCached(
        item.canonicalPath,
        item.oneDriveItemId,
    )
    if (cached != null) return cached
    val stub = item.cachedPlaceholderThumb
    if (stub == null || mediaCacheRepository.isSyncFailurePlaceholderThumbnail(stub)) return null
    return stub.takeIf { mediaCacheRepository.isDisplayableGalleryThumbFile(it) }
}

fun hasCachedThumbnailFile(
    item: GalleryDisplayItem,
    mediaCacheRepository: MediaCacheRepository,
): Boolean = cachedBrowseThumbFile(item, mediaCacheRepository) != null

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
        if (mediaCacheRepository != null) {
            item.resolvedGridVideoThumbFile(mediaCacheRepository)
                ?.takeIf { albumCoverThumbFileAccept(it, mediaCacheRepository) }
                ?.let { return it }
        }
        cachedThumb?.takeIf { thumb ->
            VideoThumbnailHelper.isDisplayableVideoThumbnailFile(thumb) &&
                (mediaCacheRepository == null || albumCoverThumbFileAccept(thumb, mediaCacheRepository))
        }?.let { return it }
        if (item.hasLocalVideoCoverOnDevice() || item.videoCountsAsLocalBrowseThumb()) {
            val uri = item.localUri ?: return null
            return DeviceVideoCover(uri, item.localMediaId)
        }
        return null
    }
    val paintableThumb = when {
        mediaCacheRepository != null -> albumCoverThumbFile(item, mediaCacheRepository)
        else -> cachedThumb?.takeIf { it.isSafeGridImageFile() }
    }
    if (paintableThumb != null) return paintableThumb
    if (item.isLocalPhotoOnDevice()) {
        item.localUri?.let { return it }
    }
    item.cachedOriginal?.takeIf { it.isSafeGridImageFile() }?.let { return it }
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
