package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import coil.memory.MemoryCache
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import java.io.File

/** Estado global: sync em massa cede CPU quando a grade está rolando ou prefetch visível. */
object GalleryGridScrollGate {
    @Volatile
    var isUserScrollingGrid: Boolean = false

    @Volatile
    var gridVisiblePrefetchPending: Boolean = false

    fun shouldDeferBulkThumbnailPrefetch(): Boolean =
        isUserScrollingGrid || gridVisiblePrefetchPending
}

val LocalGalleryGridScrolling = staticCompositionLocalOf { false }

val LocalZeroDecodeGridScroll = staticCompositionLocalOf { true }

@Composable
fun ProvideGalleryGridScrollState(
    isScrolling: Boolean,
    zeroDecodeWhileScrolling: Boolean,
    content: @Composable () -> Unit,
) {
    GalleryGridScrollGate.isUserScrollingGrid = isScrolling
    CompositionLocalProvider(
        LocalGalleryGridScrolling provides isScrolling,
        LocalZeroDecodeGridScroll provides zeroDecodeWhileScrolling,
        content = content,
    )
}

object GalleryThumbnailDecodeGate {
    /** Na rolagem: só pinta JPEG commitado ou hit quente no memory cache do Coil. */
    fun canPaintGridCellDuringScroll(
        item: GalleryDisplayItem,
        displayData: Any?,
        mediaCacheRepository: MediaCacheRepository,
        imageLoader: ImageLoader,
        memoryCacheKey: String,
    ): Boolean {
        if (mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId) != null) {
            return true
        }
        val file = displayData as? File
        if (file != null && mediaCacheRepository.isDisplayableGalleryThumbFile(file)) {
            return true
        }
        if (imageLoader.memoryCache?.get(MemoryCache.Key(memoryCacheKey)) != null) {
            return true
        }
        if (displayData is OneDriveCloudThumbnailKey) {
            return imageLoader.memoryCache?.get(MemoryCache.Key(memoryCacheKey)) != null
        }
        if (item.kind == MediaKind.VIDEO) return false
        return false
    }
}
