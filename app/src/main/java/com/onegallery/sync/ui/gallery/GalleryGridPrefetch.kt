package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo

/** Caminhos para prefetch de miniatura: visível + ~2 telas abaixo. */
fun galleryGridPrefetchPaths(
    layoutInfo: LazyGridLayoutInfo,
    entries: List<GalleryGridEntry>,
    screensAhead: Int = 2,
): List<String> {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty() || entries.isEmpty()) return emptyList()
    val first = visible.first().index
    val last = visible.last().index
    val visibleSpan = (last - first + 1).coerceAtLeast(1)
    val end = (last + visibleSpan * screensAhead).coerceAtMost(entries.lastIndex)
    if (end < first) return emptyList()
    return (first..end).mapNotNull { index ->
        when (val entry = entries.getOrNull(index)) {
            is GalleryGridEntry.Photo -> entry.item.canonicalPath
            else -> null
        }
    }
}
