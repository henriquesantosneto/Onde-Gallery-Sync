package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

/** Sinaliza rolagem da grade para adiar decode pesado e refresh do catálogo. */
object GalleryGridScrollGate {
    @Volatile
    var isUserScrollingGrid: Boolean = false

    fun shouldDeferCatalogThumbnailRefresh(): Boolean = isUserScrollingGrid

    fun shouldDeferBulkThumbnailPrefetch(): Boolean = isUserScrollingGrid
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

object GalleryThumbnailScrollPaint {
    /** Na rolagem com zero-decode: não decodificar URI/arquivo grande; só JPEG em cache. */
    fun paintModelForGridCell(
        displayData: Any?,
        item: GalleryDisplayItem,
        scrollLocked: Boolean,
        mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
    ): Any? {
        val disk = mediaCacheRepository.displayableThumbnailIfCached(
            item.canonicalPath,
            item.oneDriveItemId,
        )
        if (!scrollLocked) {
            return displayData ?: disk
        }
        disk?.let { return it }
        val file = displayData as? File
        if (file != null && mediaCacheRepository.isDisplayableGalleryThumbFile(file)) {
            return file
        }
        if (displayData is OneDriveCloudThumbnailKey) {
            return displayData
        }
        return null
    }
}
