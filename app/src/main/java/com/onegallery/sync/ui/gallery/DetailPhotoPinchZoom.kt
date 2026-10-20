package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Pinça na passagem Initial (antes do arraste do painel no mesmo ancestral).
 */
fun Modifier.detailPhotoPinchZoom(
    transform: ZoomTransformHolder,
    onPinching: (Boolean) -> Unit,
    onTransformChanged: () -> Unit,
): Modifier = pointerInput(transform) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var previousDistance = 0f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            when {
                pressed.isEmpty() -> {
                    previousDistance = 0f
                    if (pinching) {
                        pinching = false
                        onPinching(false)
                    }
                    break
                }
                pressed.size >= 2 -> {
                    if (!pinching) {
                        pinching = true
                        onPinching(true)
                    }
                    val p0 = pressed[0].position
                    val p1 = pressed[1].position
                    val distance = (p0 - p1).getDistance().coerceAtLeast(1f)
                    val centroid = (p0 + p1) / 2f
                    if (previousDistance > 0f) {
                        val zoom = distance / previousDistance
                        if (zoom.isFinite() && abs(zoom - 1f) > 0.0008f) {
                            transform.applyPinchZoom(centroid, zoom)
                            onTransformChanged()
                        }
                    }
                    previousDistance = distance
                    pressed.forEach { it.consume() }
                }
                else -> break
            }
        }
    }
}

/** Estado de zoom/pan da foto aberta (um holder por página). */
class ZoomTransformHolder {
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
        if (currentScale <= 1f || size == IntSize.Zero) return Offset.Zero
        val maxX = size.width * (currentScale - 1f) / 2f
        val maxY = size.height * (currentScale - 1f) / 2f
        return Offset(
            x = raw.x.coerceIn(-maxX, maxX),
            y = raw.y.coerceIn(-maxY, maxY),
        )
    }

    fun applyPinchZoom(centroid: Offset, zoomFactor: Float) {
        if (!zoomFactor.isFinite()) return
        val previousScale = scale
        val newScale = (previousScale * zoomFactor).coerceIn(1f, 5f)
        if (abs(newScale - previousScale) < 0.0001f) return
        val scaleFactor = newScale / previousScale
        val focal = centroid - layerCenter()
        offset = clampOffset(
            focal - (focal - offset) * scaleFactor,
            newScale,
        )
        scale = newScale
        if (newScale <= 1.001f) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    fun panBy(dragAmount: Offset) {
        if (scale <= 1.02f) return
        offset = clampOffset(offset + dragAmount, scale)
    }
}
