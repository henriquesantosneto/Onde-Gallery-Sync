package com.onegallery.sync.ui.gallery

import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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

    /** [focus] em coordenadas locais; usa [ScaleGestureDetector] (suavizado pelo sistema). */
    fun applyScaleFactor(scaleFactor: Float, focus: Offset) {
        if (!scaleFactor.isFinite() || abs(scaleFactor - 1f) < 0.001f) return
        val previousScale = scale
        val newScale = (previousScale * scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = newScale / previousScale
        val focal = focus - layerCenter()
        offset = clampOffset(
            focal - (focal - offset) * factor,
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

@androidx.compose.ui.ExperimentalComposeUiApi
@Composable
fun ZoomableMediaContainer(
    resetKey: Any?,
    modifier: Modifier = Modifier,
    interceptSingleTap: Boolean = true,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val transform = remember(resetKey) { ZoomTransformHolder() }
    val scalingFlag = remember(resetKey) { mutableStateOf(false) }
    val zoomActiveCallback = rememberUpdatedState(onZoomActiveChanged)
    var lastZoomActiveReported by remember(resetKey) { mutableStateOf(false) }

    fun reportZoomActive() {
        val active = scalingFlag.value || transform.scale > ZOOM_ACTIVE_THRESHOLD
        if (active != lastZoomActiveReported) {
            lastZoomActiveReported = active
            zoomActiveCallback.value(active)
        }
    }
    val scaleDetector = remember(resetKey, context) {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scalingFlag.value = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    transform.applyScaleFactor(
                        detector.scaleFactor,
                        Offset(detector.focusX, detector.focusY),
                    )
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    scalingFlag.value = false
                }
            },
        )
    }

    LaunchedEffect(scalingFlag.value, transform.scale) {
        reportZoomActive()
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
                .pointerInteropFilter { event ->
                    scaleDetector.onTouchEvent(event)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        -> {
                            if (event.pointerCount <= 1) {
                                scalingFlag.value = false
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            if (event.pointerCount - 1 < 2) {
                                scalingFlag.value = false
                            }
                        }
                    }
                    scalingFlag.value || event.pointerCount >= 2
                }
                .pointerInput(resetKey) {
                    detectDragGestures { change, dragAmount ->
                        if (scalingFlag.value || transform.scale <= ZOOM_ACTIVE_THRESHOLD) {
                            return@detectDragGestures
                        }
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
