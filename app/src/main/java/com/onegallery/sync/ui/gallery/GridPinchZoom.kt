package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object GridPinchZoom {
    const val MIN_SCALE = 0.55f
    const val MAX_SCALE = 1.75f
}

@Composable
fun rememberGalleryGridZoom(initial: Float, onPersist: (Float) -> Unit): Pair<Float, Modifier> {
    var scale by rememberSaveable {
        mutableFloatStateOf(initial.coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE))
    }
    val modifier = Modifier.pointerInput(Unit) {
        detectTransformGestures(
            panZoomLock = true,
        ) { _, _, zoom, _ ->
            if (zoom != 1f) {
                val next = (scale * zoom).coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE)
                if (next != scale) {
                    scale = next
                    onPersist(next)
                }
            }
        }
    }
    return scale to modifier
}

fun gridAdaptiveMinSize(baseMinDp: Float, zoomScale: Float): Dp {
    val min = (baseMinDp / zoomScale).coerceIn(72f, 260f)
    return min.dp
}
