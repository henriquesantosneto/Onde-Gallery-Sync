package com.onegallery.sync.ui.gallery

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
 * Pinça na grade: escuta no passe [PointerEventPass.Initial] (antes do scroll/cliques dos filhos).
 */
fun Modifier.gridPinchZoomDetector(
    onMultiTouchActive: (Boolean) -> Unit,
    onPinchZoom: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var previousDistance = 0f
        var hadTwoPointers = false
        while (true) {
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
                if (!pressed.any { it.pressed }) {
                    hadTwoPointers = false
                    onMultiTouchActive(false)
                } else if (!hadTwoPointers) {
                    onMultiTouchActive(false)
                }
            }
        }
    }
}
