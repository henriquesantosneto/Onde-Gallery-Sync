package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class FastScrollMarkerKind {
    YEAR,
    MONTH,
    DAY,
    SECTION,
}

data class FastScrollMarker(
    val itemIndex: Int,
    val label: String,
    val kind: FastScrollMarkerKind,
)

/** Marcadores da barra lateral a partir dos cabeçalhos da grade (ano / mês / dia). */
fun buildGalleryFastScrollMarkers(
    entries: List<GalleryGridEntry>,
    grouping: TimelineGrouping,
): List<FastScrollMarker> {
    return entries.mapIndexedNotNull { index, entry ->
        if (entry !is GalleryGridEntry.Header) return@mapIndexedNotNull null
        when (entry.header.kind) {
            TimelineHeaderKind.YEAR -> FastScrollMarker(
                itemIndex = index,
                label = entry.header.title,
                kind = FastScrollMarkerKind.YEAR,
            )
            TimelineHeaderKind.MONTH -> if (grouping == TimelineGrouping.RELAXED) {
                FastScrollMarker(
                    itemIndex = index,
                    label = entry.header.title,
                    kind = FastScrollMarkerKind.MONTH,
                )
            } else {
                null
            }
            TimelineHeaderKind.DAY -> if (grouping == TimelineGrouping.BY_DAY) {
                FastScrollMarker(
                    itemIndex = index,
                    label = entry.header.title,
                    kind = FastScrollMarkerKind.DAY,
                )
            } else {
                null
            }
        }
    }
}

fun labelForFastScrollGridPosition(
    entries: List<GalleryGridEntry>,
    itemIndex: Int,
    gridScale: Float,
): String? {
    if (entries.isEmpty() || itemIndex !in entries.indices) return null
    for (i in itemIndex downTo 0) {
        when (val entry = entries[i]) {
            is GalleryGridEntry.Header -> return entry.header.title
            is GalleryGridEntry.Photo -> {
                return timelineStickyLabel(entry.item.sortTimestamp, gridScale)
            }
        }
    }
    return null
}

val GalleryFastScrollRailWidth = 28.dp

@Composable
fun GalleryTimelineFastScrollRail(
    gridState: LazyGridState,
    entries: List<GalleryGridEntry>,
    gridScale: Float,
    timelineGrouping: TimelineGrouping,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!enabled || entries.size < 12) return

    val markers = remember(entries, timelineGrouping) {
        buildGalleryFastScrollMarkers(entries, timelineGrouping)
    }
    if (markers.isEmpty()) return

    val scope = rememberCoroutineScope()
    var railHeightPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragLabel by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val thumbPadPx = with(density) { 12.dp.toPx() }

    val thumbFraction by remember(gridState, entries, gridScale) {
        derivedStateOf {
            val info = gridState.layoutInfo
            if (info.totalItemsCount <= 1) return@derivedStateOf 0f
            val anchor = info.visibleItemsInfo.minByOrNull { it.offset.y }?.index ?: 0
            anchor.toFloat() / (info.totalItemsCount - 1).coerceAtLeast(1)
        }
    }

    fun scrollToFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val targetIndex = (clamped * (entries.lastIndex)).roundToInt()
        val snapped = markers.minByOrNull { abs(it.itemIndex - targetIndex) }?.itemIndex ?: targetIndex
        dragLabel = labelForFastScrollGridPosition(entries, snapped, gridScale)
            ?: markers.minByOrNull { abs(it.itemIndex - targetIndex) }?.label
        scope.launch {
            gridState.animateScrollToItem(snapped.coerceIn(0, entries.lastIndex))
        }
    }

    Box(
        modifier = modifier
            .width(GalleryFastScrollRailWidth)
            .fillMaxHeight()
            .onSizeChanged { railHeightPx = it.height.toFloat() },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(6.dp)
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        )

        if (railHeightPx > 0f) {
            val thumbY = (thumbFraction * railHeightPx).roundToInt()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(0, thumbY.coerceIn(0, (railHeightPx - thumbPadPx).toInt()))
                    }
                    .width(8.dp)
                    .padding(end = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (dragging) 0.95f else 0.55f,
                        ),
                    )
                    .padding(vertical = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(entries, markers, gridScale) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            if (railHeightPx > 0f) {
                                scrollToFraction(offset.y / railHeightPx)
                            }
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            if (railHeightPx > 0f) {
                                scrollToFraction(change.position.y / railHeightPx)
                            }
                        },
                    )
                },
        )

        val bubbleLabel = if (dragging) dragLabel else null
        if (!bubbleLabel.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = GalleryFastScrollRailWidth + 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = bubbleLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
