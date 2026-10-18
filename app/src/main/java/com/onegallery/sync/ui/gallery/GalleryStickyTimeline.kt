package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun rememberStickyTimelineLabel(
    gridState: LazyGridState,
    photos: List<GalleryDisplayItem>,
    gridScale: Float,
    enabled: Boolean,
): String? {
    val label by remember(gridState, photos, gridScale, enabled) {
        derivedStateOf {
            if (!enabled || photos.isEmpty()) return@derivedStateOf null
            val anchor = gridState.layoutInfo.visibleItemsInfo
                .filter { info -> info.index in photos.indices }
                .minByOrNull { it.offset.y }
                ?: return@derivedStateOf null
            val item = photos[anchor.index]
            timelineStickyLabel(item.sortTimestamp, gridScale)
        }
    }
    return label
}

@Composable
fun GalleryStickyTimelineBar(
    title: String?,
    modifier: Modifier = Modifier,
) {
    if (title.isNullOrBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
