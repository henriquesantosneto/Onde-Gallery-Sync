package com.onegallery.sync.ui.gallery

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

@Composable
fun ZoomableMediaContainer(
    resetKey: Any?,
    modifier: Modifier = Modifier,
    interceptSingleTap: Boolean = true,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scaleState = remember(resetKey) { mutableFloatStateOf(1f) }
    var scale by scaleState
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(resetKey) { mutableStateOf(IntSize.Zero) }
    val zoomActiveCallback = rememberUpdatedState(onZoomActiveChanged)

    fun layerCenter(): Offset {
        if (containerSize == IntSize.Zero) return Offset.Zero
        return Offset(containerSize.width / 2f, containerSize.height / 2f)
    }

    fun updateZoomActive(multiTouch: Boolean) {
        zoomActiveCallback.value(multiTouch || scaleState.floatValue > ZOOM_ACTIVE_THRESHOLD)
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

    fun applyZoomAtCentroid(centroid: Offset, zoomFactor: Float, pan: Offset = Offset.Zero) {
        if (!zoomFactor.isFinite()) return
        val previousScale = scaleState.floatValue
        if (abs(zoomFactor - 1f) <= 0.0005f) {
            if (pan != Offset.Zero && previousScale > MIN_SCALE) {
                offset = clampOffset(offset + pan, previousScale)
                updateZoomActive(multiTouch = false)
            }
            return
        }
        val newScale = (previousScale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val scaleFactor = if (previousScale == 0f) 1f else newScale / previousScale
        val focal = centroid - layerCenter()
        offset = clampOffset(
            focal * (1f - scaleFactor) + offset * scaleFactor + pan,
            newScale,
        )
        scaleState.floatValue = newScale
        scale = newScale
        if (newScale <= MIN_SCALE + 0.001f) {
            scaleState.floatValue = MIN_SCALE
            scale = MIN_SCALE
            offset = Offset.Zero
        }
        updateZoomActive(multiTouch = newScale > ZOOM_ACTIVE_THRESHOLD)
    }

    Box(
        modifier = modifier.onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleState.floatValue
                    scaleY = scaleState.floatValue
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .pointerInput(resetKey, containerSize) {
                    awaitPointerEventScope {
                        var previousDistance = 0f
                        var pinching = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            when {
                                pressed.size >= 2 -> {
                                    pinching = true
                                    updateZoomActive(multiTouch = true)
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val distance = (p0 - p1).getDistance()
                                    val centroid = (p0 + p1) / 2f
                                    if (previousDistance > 0f) {
                                        val zoom = distance / previousDistance
                                        applyZoomAtCentroid(centroid, zoom)
                                    }
                                    previousDistance = distance
                                    pressed.forEach { it.consume() }
                                }
                                pressed.isEmpty() -> {
                                    previousDistance = 0f
                                    pinching = false
                                    updateZoomActive(multiTouch = false)
                                }
                                pinching -> Unit
                                else -> previousDistance = 0f
                            }
                        }
                    }
                }
                .pointerInput(resetKey, scale) {
                    if (scale <= ZOOM_ACTIVE_THRESHOLD) return@pointerInput
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset = clampOffset(offset + dragAmount, scaleState.floatValue)
                        updateZoomActive(multiTouch = false)
                    }
                }
                .pointerInput(resetKey, scale, containerSize) {
                    if (scale <= ZOOM_ACTIVE_THRESHOLD) return@pointerInput
                    detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                        applyZoomAtCentroid(centroid, zoom, pan)
                        updateZoomActive(multiTouch = true)
                    }
                }
                .pointerInput(resetKey, interceptSingleTap, scale) {
                    if (!interceptSingleTap) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            if (abs(scaleState.floatValue - MIN_SCALE) < 0.05f) {
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
