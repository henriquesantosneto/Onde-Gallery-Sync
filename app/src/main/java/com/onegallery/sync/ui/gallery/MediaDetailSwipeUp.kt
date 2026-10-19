package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

/** Abre detalhes ao deslizar o dedo para cima na área da foto (sem aba visível). */
fun Modifier.openDetailsOnSwipeUp(
    enabled: Boolean,
    onSwipeUp: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var upwardPx = 0f
            var opened = false
            drag(down.id) { change ->
                if (opened) return@drag
                val dy = change.positionChange().y
                if (dy < 0f) {
                    upwardPx -= dy
                } else if (dy > 6f) {
                    upwardPx = 0f
                }
                if (upwardPx > 96f) {
                    opened = true
                    onSwipeUp()
                }
            }
        }
    }
}
