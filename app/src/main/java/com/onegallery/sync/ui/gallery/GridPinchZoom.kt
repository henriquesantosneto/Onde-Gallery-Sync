package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object GridPinchZoom {
    const val MIN_SCALE = 0.55f
    const val MAX_SCALE = 1.75f
}

data class GalleryGridPinchZoom(
    val scale: Float,
    val gridModifier: Modifier,
    /** Enquanto true, cliques e long-press nos thumbnails ficam desligados. */
    val blockItemGestures: Boolean,
)

@Composable
fun rememberGalleryGridZoom(initial: Float, onPersist: (Float) -> Unit): GalleryGridPinchZoom {
    var scale by rememberSaveable {
        mutableFloatStateOf(initial.coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE))
    }
    var multiTouch by remember { mutableStateOf(false) }
    val nestedScrollConnection = remember(multiTouch) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (multiTouch && source == NestedScrollSource.Drag) {
                    return available
                }
                return Offset.Zero
            }
        }
    }
    val gridModifier = Modifier
        .nestedScroll(nestedScrollConnection)
        .gridPinchZoomDetector(
            onMultiTouchActive = { active -> multiTouch = active },
            onPinchZoom = { zoom ->
                val next = (scale * zoom).coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE)
                if (next != scale) {
                    scale = next
                    onPersist(next)
                }
            },
        )
    return GalleryGridPinchZoom(
        scale = scale,
        gridModifier = gridModifier,
        blockItemGestures = multiTouch,
    )
}

/** Escala maior → células maiores (menos colunas), como na galeria Samsung. */
fun gridAdaptiveMinSize(baseMinDp: Float, zoomScale: Float): Dp {
    val min = (baseMinDp * zoomScale).coerceIn(72f, 260f)
    return min.dp
}
