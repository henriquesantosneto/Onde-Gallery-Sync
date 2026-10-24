package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Estado global: sync em massa cede CPU quando a grade está rolando ou prefetch visível. */
object GalleryGridScrollGate {
    @Volatile
    var isUserScrollingGrid: Boolean = false

    @Volatile
    var gridVisiblePrefetchPending: Boolean = false

    fun shouldDeferBulkThumbnailPrefetch(): Boolean =
        isUserScrollingGrid || gridVisiblePrefetchPending

    fun shouldDeferCatalogThumbnailRefresh(): Boolean = isUserScrollingGrid
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

