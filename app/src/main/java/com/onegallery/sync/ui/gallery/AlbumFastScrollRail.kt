package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.abs

@Composable
fun AlbumGridFastScrollRail(
    gridState: LazyGridState,
    itemCount: Int,
    markers: List<FastScrollMarker>,
    modifier: Modifier = Modifier,
) {
    if (itemCount < 8 || markers.isEmpty()) return
    GridFastScrollRail(
        gridState = gridState,
        itemCount = itemCount,
        lastIndex = (itemCount - 1).coerceAtLeast(0),
        markers = markers,
        modifier = modifier,
        labelForScrollIndex = { index ->
            markers.minByOrNull { abs(it.itemIndex - index) }?.label
        },
    )
}
