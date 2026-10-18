package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Na fase Initial, detecta dois ou mais dedos, notifica o caller e consome o evento para que
 * pager, LazyGrid e clickables não tratem como swipe ou toque.
 */
fun Modifier.observeMultiTouchPinch(
    onMultiTouchActive: (Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val active = event.changes.count { it.pressed } >= 2
            onMultiTouchActive(active)
            if (active) {
                event.changes.forEach { change -> change.consume() }
            }
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
                    val active = event.changes.count { it.pressed } >= 2
                    onMultiTouchActive(active)
                    if (active) {
                        event.changes.forEach { change -> change.consume() }
                    }
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
