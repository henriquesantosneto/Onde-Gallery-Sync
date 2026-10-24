package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

private const val IMMERSIVE_ZOOM_IDLE_SCALE = 1.05f

/**
 * Toque simples na passagem Final — não compete com pinça/pager no Main.
 * Só alterna imersivo com zoom ~1 e se nenhum filho consumiu o toque (ex.: botões).
 */
@Composable
fun Modifier.detailImmersiveTap(
    zoomTransform: ZoomTransformHolder,
    onToggleImmersive: () -> Unit,
): Modifier {
    val transformRef = rememberUpdatedState(zoomTransform)
    val onTapRef = rememberUpdatedState(onToggleImmersive)
    return pointerInput(zoomTransform, onToggleImmersive) {
        awaitEachGesture {
            val touchSlop = viewConfiguration.touchSlop
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var sumMove = Offset.Zero
            var cancelled = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    val scale = transformRef.value.scale
                    if (
                        !cancelled &&
                        !change.isConsumed &&
                        sumMove.getDistance() < touchSlop &&
                        abs(scale - 1f) < (IMMERSIVE_ZOOM_IDLE_SCALE - 1f)
                    ) {
                        onTapRef.value()
                    }
                    break
                }
                sumMove += change.positionChange()
                if (sumMove.getDistance() >= touchSlop) {
                    cancelled = true
                }
            }
        }
    }
}
