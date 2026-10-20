package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val ZOOM_ACTIVE_THRESHOLD = 1.02f
/** Ignora ruído de dedo / sensor abaixo disto (ratio por frame). */
private const val MIN_ZOOM_DELTA = 0.012f

private class ZoomTransformHolder {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var containerSize by mutableStateOf(IntSize.Zero)

    private fun layerCenter(): Offset {
        val size = containerSize
        if (size == IntSize.Zero) return Offset.Zero
        return Offset(size.width / 2f, size.height / 2f)
    }

    private fun clampOffset(raw: Offset, currentScale: Float): Offset {
        val size = containerSize
        if (currentScale <= MIN_SCALE || size == IntSize.Zero) return Offset.Zero
        val maxX = size.width * (currentScale - 1f) / 2f
        val maxY = size.height * (currentScale - 1f) / 2f
        return Offset(
            x = raw.x.coerceIn(-maxX, maxX),
            y = raw.y.coerceIn(-maxY, maxY),
        )
    }

    fun applyPinchAt(centroid: Offset, zoomFactor: Float) {
        if (!zoomFactor.isFinite() || abs(zoomFactor - 1f) < MIN_ZOOM_DELTA) return
        val previousScale = scale
        val newScale = (previousScale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (abs(newScale - previousScale) < 0.0001f) return
        val scaleFactor = newScale / previousScale
        val focal = centroid - layerCenter()
        offset = clampOffset(
            focal * (1f - scaleFactor) + offset * scaleFactor,
            newScale,
        )
        scale = newScale
        if (newScale <= MIN_SCALE + 0.001f) {
            scale = MIN_SCALE
            offset = Offset.Zero
        }
    }

    fun panBy(dragAmount: Offset) {
        if (scale <= ZOOM_ACTIVE_THRESHOLD) return
        offset = clampOffset(offset + dragAmount, scale)
    }
}

@Composable
fun ZoomableMediaContainer(
    resetKey: Any?,
    modifier: Modifier = Modifier,
    interceptSingleTap: Boolean = true,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val transform = remember(resetKey) { ZoomTransformHolder() }
    val zoomActiveCallback = rememberUpdatedState(onZoomActiveChanged)
    var lastZoomActiveReported by remember(resetKey) { mutableStateOf(false) }

    fun reportZoomActive(multiTouch: Boolean) {
        val active = multiTouch || transform.scale > ZOOM_ACTIVE_THRESHOLD
        if (active != lastZoomActiveReported) {
            lastZoomActiveReported = active
            zoomActiveCallback.value(active)
        }
    }

    Box(
        modifier = modifier.onSizeChanged { transform.containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offset.x
                    translationY = transform.offset.y
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .pointerInput(resetKey) {
                    awaitEachGesture {
                        var previousDistance = 0f
                        var centroidSmooth: Offset? = null
                        try {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pressed = event.changes.filter { it.pressed }
                                when {
                                    pressed.size >= 2 -> {
                                        reportZoomActive(multiTouch = true)
                                        val p0 = pressed[0].position
                                        val p1 = pressed[1].position
                                        val distance = (p0 - p1).getDistance().coerceAtLeast(1f)
                                        val centroid = (p0 + p1) / 2f
                                        centroidSmooth = when (val prev = centroidSmooth) {
                                            null -> centroid
                                            else -> prev * 0.55f + centroid * 0.45f
                                        }
                                        if (previousDistance > 0f) {
                                            val zoom = distance / previousDistance
                                            transform.applyPinchAt(centroidSmooth!!, zoom)
                                        }
                                        previousDistance = distance
                                        pressed.forEach { it.consume() }
                                    }
                                    pressed.isEmpty() -> break
                                    else -> previousDistance = 0f
                                }
                            } while (true)
                        } finally {
                            centroidSmooth = null
                            reportZoomActive(multiTouch = false)
                        }
                    }
                }
                .pointerInput(resetKey) {
                    detectDragGestures { change, dragAmount ->
                        if (transform.scale <= ZOOM_ACTIVE_THRESHOLD) return@detectDragGestures
                        change.consume()
                        transform.panBy(dragAmount)
                    }
                }
                .pointerInput(resetKey, interceptSingleTap) {
                    if (!interceptSingleTap) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            if (abs(transform.scale - MIN_SCALE) < 0.05f) {
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
