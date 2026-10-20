package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val ZOOM_ACTIVE_THRESHOLD = 1.02f

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

    fun applyTransform(centroid: Offset, zoomFactor: Float, pan: Offset) {
        if (!zoomFactor.isFinite()) return
        val previousScale = scale
        val hasZoom = abs(zoomFactor - 1f) >= 0.002f
        val hasPan = pan != Offset.Zero
        if (!hasZoom && !hasPan) return

        val newScale = if (hasZoom) {
            (previousScale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        } else {
            previousScale
        }
        if (hasZoom && previousScale > 0f) {
            val scaleFactor = newScale / previousScale
            val focal = centroid - layerCenter()
            offset = clampOffset(
                focal - (focal - offset) * scaleFactor + if (hasPan) pan else Offset.Zero,
                newScale,
            )
            scale = newScale
        } else if (hasPan && previousScale > ZOOM_ACTIVE_THRESHOLD) {
            offset = clampOffset(offset + pan, previousScale)
        }
        if (scale <= MIN_SCALE + 0.001f) {
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

    fun reportZoomActive() {
        val active = transform.scale > ZOOM_ACTIVE_THRESHOLD
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
                    detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                        transform.applyTransform(centroid, zoom, pan)
                        reportZoomActive()
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
