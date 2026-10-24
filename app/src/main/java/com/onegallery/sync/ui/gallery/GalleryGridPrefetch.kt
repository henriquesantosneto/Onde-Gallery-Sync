package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo

/** Caminhos visíveis na grade (sem extensão abaixo). */
fun galleryGridVisiblePaths(
    layoutInfo: LazyGridLayoutInfo,
    entries: List<GalleryGridEntry>,
): List<String> = galleryGridPrefetchPaths(layoutInfo, entries, screensAhead = 0)

/** Caminhos para prefetch de miniatura: visível + ~N telas abaixo. */
fun galleryGridPrefetchPaths(
    layoutInfo: LazyGridLayoutInfo,
    entries: List<GalleryGridEntry>,
    screensAhead: Int = 2,
): List<String> {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty() || entries.isEmpty()) return emptyList()
    val first = visible.first().index
    val last = visible.last().index
    if (screensAhead <= 0) {
        return (first..last).mapNotNull { index -> entryPathAt(entries, index) }
    }
    val visibleSpan = (last - first + 1).coerceAtLeast(1)
    val end = (last + visibleSpan * screensAhead).coerceAtMost(entries.lastIndex)
    if (end < first) return emptyList()
    return (first..end).mapNotNull { index ->
        entryPathAt(entries, index)
    }
}

private fun entryPathAt(entries: List<GalleryGridEntry>, index: Int): String? {
    return when (val entry = entries.getOrNull(index)) {
        is GalleryGridEntry.Photo -> entry.item.canonicalPath
        else -> null
    }
}
