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

@Composable
fun AlbumGridFastScrollRail(
    gridState: LazyGridState,
    itemCount: Int,
    markers: List<FastScrollMarker>,
    modifier: Modifier = Modifier,
) {
    if (itemCount < 8 || markers.isEmpty()) return

    val scope = rememberCoroutineScope()
    var railHeightPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragLabel by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val thumbPadPx = with(density) { 12.dp.toPx() }

    val thumbFraction by remember(gridState, itemCount) {
        derivedStateOf {
            val info = gridState.layoutInfo
            if (info.totalItemsCount <= 1) return@derivedStateOf 0f
            val anchor = info.visibleItemsInfo.minByOrNull { it.offset.y }?.index ?: 0
            anchor.toFloat() / (info.totalItemsCount - 1).coerceAtLeast(1)
        }
    }

    fun scrollToFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val targetIndex = (clamped * (itemCount - 1).coerceAtLeast(0)).roundToInt()
        val marker = markers.minByOrNull { abs(it.itemIndex - targetIndex) }
        dragLabel = marker?.label
        val scrollIndex = marker?.itemIndex ?: targetIndex
        scope.launch {
            gridState.animateScrollToItem(scrollIndex.coerceIn(0, itemCount - 1))
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
                .pointerInput(markers, itemCount) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            if (railHeightPx > 0f) scrollToFraction(offset.y / railHeightPx)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            if (railHeightPx > 0f) scrollToFraction(change.position.y / railHeightPx)
                        },
                    )
                },
        )

        if (dragging && !dragLabel.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = GalleryFastScrollRailWidth + 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = dragLabel.orEmpty(),
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
