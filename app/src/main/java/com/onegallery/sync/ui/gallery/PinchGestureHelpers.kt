package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Detecta dois ou mais dedos cedo (Initial) para bloquear pager/scroll/cliques via estado,
 * **sem** consumir o evento — assim gestos de zoom ainda recebem a pinça.
 */
fun Modifier.observeMultiTouchPinch(
    onMultiTouchActive: (Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val active = event.changes.count { it.pressed } >= 2
            onMultiTouchActive(active)
        }
    }
}

/**
 * Pinça na [LazyVerticalGrid]: distância entre dois dedos (não depende de [detectTransformGestures],
 * que perde para o scroll interno da grade).
 */
fun Modifier.gridPinchZoomDetector(
    onMultiTouchActive: (Boolean) -> Unit,
    onPinchZoom: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        try {
            onMultiTouchActive(false)
            var previousDistance = 0f
            var hadTwoPointers = false

            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }

                if (pressed.size >= 2) {
                    hadTwoPointers = true
                    onMultiTouchActive(true)
                    val p0 = pressed[0].position
                    val p1 = pressed[1].position
                    val distance = (p0 - p1).getDistance()
                    if (previousDistance > 0f) {
                        val zoom = distance / previousDistance
                        if (zoom.isFinite() && abs(zoom - 1f) > 0.001f) {
                            onPinchZoom(zoom)
                        }
                    }
                    previousDistance = distance
                    pressed.forEach { it.consume() }
                } else {
                    previousDistance = 0f
                    if (!hadTwoPointers) {
                        onMultiTouchActive(false)
                    }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            onMultiTouchActive(false)
        }
    }
}
