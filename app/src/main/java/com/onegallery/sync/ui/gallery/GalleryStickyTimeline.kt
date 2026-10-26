package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/** Altura reservada no topo da grade + overlay da barra de data. */
val GalleryStickyTimelineBarHeight: Dp = 48.dp

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
    var label by remember(gridState, entries, gridScale, enabled) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(gridState, entries, gridScale, enabled, stickyBarHeightPx) {
        if (!enabled || entries.isEmpty() || stickyBarHeightPx <= 0) {
            label = null
            return@LaunchedEffect
        }
        snapshotFlow {
            var bestIndex = -1
            var bestTop = Int.MAX_VALUE
            val visible = gridState.layoutInfo.visibleItemsInfo
            for (index in visible.indices) {
                val info = visible[index]
                if (info.index !in entries.indices) continue
                if (info.offset.y < bestTop) {
                    bestTop = info.offset.y
                    bestIndex = info.index
                }
            }
            bestIndex
        }
            .distinctUntilChanged()
            .collect { index ->
                label = if (index < 0) null else stickyLabelForGridIndex(entries, index, gridScale)
            }
    }
    return TimelineStickyUiState(label, emptySet())
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
