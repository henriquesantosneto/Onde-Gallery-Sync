package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** Arraste horizontal (pager) ou vertical (painel de detalhes) — não é toque para imersivo. */
fun immersiveToggleCancelledByMovement(sumMove: Offset, touchSlop: Float): Boolean {
    if (sumMove.getDistance() >= touchSlop) return true
    if (abs(sumMove.x) > abs(sumMove.y) * 1.15f && abs(sumMove.x) > touchSlop * 0.35f) return true
    if (abs(sumMove.y) > abs(sumMove.x) * 1.15f && abs(sumMove.y) > touchSlop * 0.35f) return true
    return false
}

/** Toque seco na passagem Final (foto) — ignora gestos consumidos pelo pager/painel. */
suspend fun PointerInputScope.detectImmersiveToggleTapFinalPass(
    onToggle: () -> Unit,
) {
    awaitEachGesture {
        val touchSlop = viewConfiguration.touchSlop
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var sumMove = Offset.Zero
        var cancelled = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed) cancelled = true
            if (!change.pressed) {
                if (
                    !cancelled &&
                    !change.isConsumed &&
                    !immersiveToggleCancelledByMovement(sumMove, touchSlop)
                ) {
                    onToggle()
                }
                break
            }
            sumMove += change.positionChange()
            if (immersiveToggleCancelledByMovement(sumMove, touchSlop)) {
                cancelled = true
            }
        }
    }
}

/** Toque seco na área do vídeo (Main) — mesma regra anti-arraste. */
suspend fun PointerInputScope.detectImmersiveToggleTapMainPass(onToggle: () -> Unit) {
    awaitEachGesture {
        val touchSlop = viewConfiguration.touchSlop
        awaitFirstDown(requireUnconsumed = false)
        var sumMove = Offset.Zero
        var sawMultiTouch = false
        var cancelled = false
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.any { it.isConsumed }) cancelled = true
            if (pressed.size >= 2) sawMultiTouch = true
            else if (pressed.size == 1) {
                sumMove += pressed.first().positionChange()
                if (immersiveToggleCancelledByMovement(sumMove, touchSlop)) cancelled = true
            }
        } while (event.changes.any { it.pressed })
        if (!sawMultiTouch && !cancelled && !immersiveToggleCancelledByMovement(sumMove, touchSlop)) {
            onToggle()
        }
    }
}
