package com.onegallery.sync.ui.gallery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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

/** Espaço reservado na grade (barra repousando). */
val GalleryFastScrollRailWidth = 32.dp

private val GalleryFastScrollRailWidthActive = 56.dp
private val GalleryFastScrollTrackWidthIdle = 8.dp
private val GalleryFastScrollTrackWidthActive = 14.dp

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

    GridFastScrollRail(
        gridState = gridState,
        itemCount = entries.size,
        lastIndex = entries.lastIndex,
        markers = markers,
        modifier = modifier,
        labelForScrollIndex = { index ->
            labelForFastScrollGridPosition(entries, index, gridScale)
        },
    )
}

@Composable
fun GridFastScrollRail(
    gridState: LazyGridState,
    itemCount: Int,
    lastIndex: Int,
    markers: List<FastScrollMarker>,
    labelForScrollIndex: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    if (itemCount < 8 || markers.isEmpty()) return

    val scope = rememberCoroutineScope()
    var railHeightPx by remember { mutableFloatStateOf(0f) }
    val railHeightState = rememberUpdatedState(railHeightPx)
    var dragging by remember { mutableStateOf(false) }
    var dragLabel by remember { mutableStateOf<String?>(null) }
    var dragThumbFraction by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val thumbPadPx = with(density) { 20.dp.toPx() }

    val containerWidth by animateDpAsState(
        targetValue = if (dragging) GalleryFastScrollRailWidthActive else GalleryFastScrollRailWidth,
        label = "fastScrollRailWidth",
    )
    val trackWidth by animateDpAsState(
        targetValue = if (dragging) GalleryFastScrollTrackWidthActive else GalleryFastScrollTrackWidthIdle,
        label = "fastScrollTrackWidth",
    )

    val scrollThumbFraction by remember(gridState, itemCount) {
        derivedStateOf {
            val info = gridState.layoutInfo
            if (info.totalItemsCount <= 1) return@derivedStateOf 0f
            val anchor = info.visibleItemsInfo.minByOrNull { it.offset.y }?.index ?: 0
            anchor.toFloat() / (info.totalItemsCount - 1).coerceAtLeast(1)
        }
    }
    val thumbFraction = if (dragging) dragThumbFraction else scrollThumbFraction

    fun resolveScrollIndex(fraction: Float): Int {
        val clamped = fraction.coerceIn(0f, 1f)
        val targetIndex = (clamped * lastIndex.coerceAtLeast(0)).roundToInt()
        return markers.minByOrNull { abs(it.itemIndex - targetIndex) }?.itemIndex ?: targetIndex
    }

    fun applyScrollAtFraction(fraction: Float, animate: Boolean) {
        val clamped = fraction.coerceIn(0f, 1f)
        dragThumbFraction = clamped
        val scrollIndex = resolveScrollIndex(clamped).coerceIn(0, lastIndex)
        dragLabel = labelForScrollIndex(scrollIndex)
            ?: markers.minByOrNull { abs(it.itemIndex - scrollIndex) }?.label
        scope.launch {
            if (animate) {
                gridState.animateScrollToItem(scrollIndex)
            } else {
                gridState.scrollToItem(scrollIndex)
            }
        }
    }

    Box(
        modifier = modifier
            .width(containerWidth)
            .fillMaxHeight()
            .onSizeChanged { railHeightPx = it.height.toFloat() },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(trackWidth)
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(trackWidth / 2))
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (dragging) 0.22f else 0.14f,
                    ),
                ),
        )

        if (railHeightPx > 0f) {
            val maxThumbY = (railHeightPx - thumbPadPx).coerceAtLeast(0f)
            val thumbY = (thumbFraction * maxThumbY).roundToInt()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbY) }
                    .width(trackWidth + 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (dragging) 1f else 0.65f,
                        ),
                    )
                    .padding(vertical = if (dragging) 10.dp else 7.dp),
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(itemCount, markers, lastIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        val height = railHeightState.value
                        if (height > 0f) {
                            applyScrollAtFraction(down.position.y / height, animate = false)
                        }
                        var accumulatedY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                change.consume()
                            }
                            accumulatedY = (accumulatedY + delta.y).coerceIn(0f, height)
                            if (height > 0f) {
                                applyScrollAtFraction(accumulatedY / height, animate = false)
                            }
                        }
                        dragging = false
                    }
                },
        )

        val bubbleLabel = if (dragging) dragLabel else null
        if (!bubbleLabel.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = containerWidth + 6.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = bubbleLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
