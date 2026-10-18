package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Detecta dois ou mais dedos cedo (Initial) para bloquear pager/scroll/cliques via estado,
 * **sem** consumir o evento — assim [detectTransformGestures] ainda recebe a pinça.
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

fun Modifier.gridPinchZoomDetector(
    onMultiTouchActive: (Boolean) -> Unit,
    onPinchZoom: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    coroutineScope {
        launch {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    onMultiTouchActive(event.changes.count { it.pressed } >= 2)
                }
            }
        }
        detectTransformGestures(
            panZoomLock = true,
        ) { _, _, zoom, _ ->
            if (zoom != 1f) {
                onMultiTouchActive(true)
                onPinchZoom(zoom)
            }
        }
    }
}
