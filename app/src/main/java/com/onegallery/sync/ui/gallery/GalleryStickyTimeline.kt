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

fun stickyLabelForGridIndex(
    entries: List<GalleryGridEntry>,
    index: Int,
    gridScale: Float,
): String? {
    if (index !in entries.indices) return null
    for (i in index downTo 0) {
        when (val entry = entries[i]) {
            is GalleryGridEntry.Header -> {
                if (entry.header.kind == TimelineHeaderKind.YEAR) continue
                return entry.header.title
            }
            is GalleryGridEntry.Photo -> {
                return timelineStickyLabel(entry.item.sortTimestamp, gridScale)
            }
        }
    }
    return null
}

data class TimelineStickyUiState(
    val label: String?,
    /** Cabeçalhos da grade já cobertos pela barra fixa (evita “Hoje” duplicado). */
    val hiddenHeaderIds: Set<String>,
)

@Composable
fun rememberTimelineStickyUiState(
    gridState: LazyGridState,
    entries: List<GalleryGridEntry>,
    gridScale: Float,
    enabled: Boolean,
    stickyBarHeightPx: Int,
): TimelineStickyUiState {
    val state by remember(gridState, entries, gridScale, enabled, stickyBarHeightPx) {
        derivedStateOf {
            if (!enabled || entries.isEmpty() || stickyBarHeightPx <= 0) {
                return@derivedStateOf TimelineStickyUiState(null, emptySet())
            }
            val visible = gridState.layoutInfo.visibleItemsInfo
                .filter { info -> info.index in entries.indices }
            val anchor = visible.minByOrNull { it.offset.y }
                ?: return@derivedStateOf TimelineStickyUiState(null, emptySet())
            val label = stickyLabelForGridIndex(entries, anchor.index, gridScale)
            val hiddenHeaderIds = visible.mapNotNull { info ->
                when (val entry = entries[info.index]) {
                    is GalleryGridEntry.Header -> {
                        if (info.offset.y < stickyBarHeightPx) entry.header.id else null
                    }
                    is GalleryGridEntry.Photo -> null
                }
            }.toSet()
            TimelineStickyUiState(label, hiddenHeaderIds)
        }
    }
    return state
}

@Composable
fun GalleryStickyTimelineBar(
    title: String?,
    modifier: Modifier = Modifier,
) {
    if (title.isNullOrBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
