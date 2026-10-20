package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
    val zoomActiveCallback = rememberUpdatedState(onZoomActiveChanged)
    val transformState = rememberUpdatedState(transform)
    var lastZoomActiveReported by remember(resetKey) { mutableStateOf(false) }

    fun reportZoomActive(multiTouch: Boolean) {
        val active = multiTouch || transformState.value.scale > ZOOM_ACTIVE_THRESHOLD
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
                    awaitPointerEventScope {
                        var previousDistance = 0f
                        var pinching = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            when {
                                pressed.size >= 2 -> {
                                    if (!pinching) {
                                        pinching = true
                                        reportZoomActive(multiTouch = true)
                                    }
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val distance = (p0 - p1).getDistance().coerceAtLeast(1f)
                                    if (previousDistance > 0f) {
                                        val zoom = distance / previousDistance
                                        if (zoom.isFinite() && abs(zoom - 1f) > 0.0008f) {
                                            transformState.value.applyPinchZoom(
                                                (p0 + p1) / 2f,
                                                zoom,
                                            )
                                        }
                                    }
                                    previousDistance = distance
                                    pressed.forEach { it.consume() }
                                }
                                pressed.isEmpty() -> {
                                    previousDistance = 0f
                                    if (pinching) {
                                        pinching = false
                                        reportZoomActive(multiTouch = false)
                                    }
                                }
                                else -> {
                                    if (!pinching) previousDistance = 0f
                                }
                            }
                        }
                    }
                }
                .pointerInput(resetKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (transformState.value.scale <= ZOOM_ACTIVE_THRESHOLD) return@awaitEachGesture
                        val touchSlop = viewConfiguration.touchSlop
                        var pastSlop = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (transformState.value.scale <= ZOOM_ACTIVE_THRESHOLD) break
                            val delta = change.positionChange()
                            if (!pastSlop) {
                                if (kotlin.math.hypot(delta.x.toDouble(), delta.y.toDouble()) < touchSlop) {
                                    continue
                                }
                                pastSlop = true
                            }
                            change.consume()
                            transformState.value.panBy(delta)
                        }
                    }
                }
                .pointerInput(resetKey, interceptSingleTap) {
                    if (!interceptSingleTap) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            if (abs(transformState.value.scale - MIN_SCALE) < 0.05f) {
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
