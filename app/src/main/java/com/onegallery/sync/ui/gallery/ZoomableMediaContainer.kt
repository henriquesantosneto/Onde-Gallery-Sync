package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs

private const val MIN_SCALE = 1f
private const val ZOOM_ACTIVE_THRESHOLD = 1.02f

/**
 * Pinça/pan só na mídia: consome toque com 2+ dedos ou quando já ampliado.
 * Um dedo em escala 1 não consome → pager horizontal funciona.
 */
@Composable
fun ZoomableMediaContainer(
    resetKey: Any?,
    transform: ZoomTransformHolder,
    modifier: Modifier = Modifier,
    interceptSingleTap: Boolean = true,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val transformRef = rememberUpdatedState(transform)
    val onZoomRef = rememberUpdatedState(onZoomActiveChanged)
    var lastZoomActive by remember(resetKey) { mutableStateOf(false) }

    fun publishZoomActive(active: Boolean) {
        if (active != lastZoomActive) {
            lastZoomActive = active
            onZoomRef.value(active)
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
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            val t = transformRef.value
                            when {
                                pressed.size >= 2 -> {
                                    publishZoomActive(true)
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (abs(zoom - 1f) > 0.001f) {
                                        val centroid = pressed.fold(Offset.Zero) { acc, c ->
                                            acc + c.position
                                        } / pressed.size.toFloat()
                                        t.applyPinchZoom(centroid, zoom)
                                    }
                                    if (t.scale > ZOOM_ACTIVE_THRESHOLD && pan != Offset.Zero) {
                                        t.panBy(pan)
                                    }
                                    pressed.forEach { it.consume() }
                                }
                                pressed.size == 1 && t.scale > ZOOM_ACTIVE_THRESHOLD -> {
                                    publishZoomActive(true)
                                    val change = pressed.first()
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        change.consume()
                                        t.panBy(delta)
                                    }
                                }
                                pressed.isEmpty() -> {
                                    publishZoomActive(t.scale > ZOOM_ACTIVE_THRESHOLD)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        publishZoomActive(
                            transformRef.value.scale > ZOOM_ACTIVE_THRESHOLD,
                        )
                    }
                }
                .pointerInput(resetKey, interceptSingleTap) {
                    if (!interceptSingleTap) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            if (abs(transformRef.value.scale - MIN_SCALE) < 0.05f) {
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
