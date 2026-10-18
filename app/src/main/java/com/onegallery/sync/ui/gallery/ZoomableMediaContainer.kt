package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val ZOOM_ACTIVE_THRESHOLD = 1.02f

@Composable
fun ZoomableMediaContainer(
    resetKey: Any?,
    modifier: Modifier = Modifier,
    interceptSingleTap: Boolean = true,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(resetKey) { mutableStateOf(IntSize.Zero) }

    fun updateZoomActive(multiTouch: Boolean) {
        onZoomActiveChanged(multiTouch || scale > ZOOM_ACTIVE_THRESHOLD)
    }

    fun clampOffset(raw: Offset, currentScale: Float): Offset {
        if (currentScale <= MIN_SCALE || containerSize == IntSize.Zero) return Offset.Zero
        val maxX = containerSize.width * (currentScale - 1f) / 2f
        val maxY = containerSize.height * (currentScale - 1f) / 2f
        return Offset(
            x = raw.x.coerceIn(-maxX, maxX),
            y = raw.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = modifier.onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(resetKey) {
                    coroutineScope {
                        launch {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val multi = event.changes.count { it.pressed } >= 2
                                    updateZoomActive(multi)
                                }
                            }
                        }
                        launch {
                            awaitEachGesture {
                                try {
                                    var previousDistance = 0f
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            updateZoomActive(multiTouch = true)
                                            val p0 = pressed[0].position
                                            val p1 = pressed[1].position
                                            val distance = (p0 - p1).getDistance()
                                            if (previousDistance > 0f) {
                                                val zoom = distance / previousDistance
                                                if (zoom.isFinite() && abs(zoom - 1f) > 0.001f) {
                                                    val previousScale = scale
                                                    val newScale = (previousScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                                    val centroid = (p0 + p1) / 2f
                                                    val scaleFactor =
                                                        if (previousScale == 0f) 1f else newScale / previousScale
                                                    offset = clampOffset(
                                                        (offset + centroid) * scaleFactor - centroid,
                                                        newScale,
                                                    )
                                                    scale = newScale
                                                    if (scale <= MIN_SCALE + 0.001f) {
                                                        scale = MIN_SCALE
                                                        offset = Offset.Zero
                                                    }
                                                    updateZoomActive(multiTouch = true)
                                                }
                                            }
                                            previousDistance = distance
                                        } else {
                                            previousDistance = 0f
                                        }
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    updateZoomActive(multiTouch = false)
                                }
                            }
                        }
                    }
                }
                .pointerInput(resetKey, scale) {
                    if (scale <= ZOOM_ACTIVE_THRESHOLD) return@pointerInput
                    detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                        updateZoomActive(multiTouch = true)
                        val previousScale = scale
                        val newScale = (previousScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        val scaleFactor = if (previousScale == 0f) 1f else newScale / previousScale
                        offset = clampOffset(
                            (offset + centroid) * scaleFactor - centroid + pan,
                            newScale,
                        )
                        scale = newScale
                        if (scale <= MIN_SCALE + 0.001f) {
                            scale = MIN_SCALE
                            offset = Offset.Zero
                        }
                        updateZoomActive(multiTouch = true)
                    }
                }
                .pointerInput(resetKey, interceptSingleTap, scale) {
                    if (!interceptSingleTap) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            if (abs(scale - MIN_SCALE) < 0.05f) {
                                onSingleTap()
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}
