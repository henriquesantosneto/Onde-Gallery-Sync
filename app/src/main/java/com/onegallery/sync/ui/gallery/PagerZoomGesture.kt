package com.onegallery.sync.ui.gallery

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Desativa o swipe do pager assim que um segundo dedo encosta, antes da pinça ser reconhecida.
 */
fun Modifier.notifyPagerWhenMultiTouch(onZoomActiveChanged: (Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    onZoomActiveChanged(true)
                }
            }
        }
    }
