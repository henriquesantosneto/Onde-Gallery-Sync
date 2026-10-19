package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

private const val OPEN_DETAILS_UPWARD_PX = 72f
private const val VERTICAL_DOMINANCE_RATIO = 1.35f

/**
 * Deslize para cima abre detalhes. Trava no eixo vertical cedo para não “brigar”
 * com o pager ou o carrossel horizontal; vale na foto e na faixa de miniaturas.
 */
fun Modifier.openDetailsOnSwipeUp(
    enabled: Boolean,
    onSwipeUp: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var sumX = 0f
            var sumY = 0f
            var lockedVerticalUp = false
            var passedToHorizontal = false
            var opened = false

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (opened || passedToHorizontal) break

                val delta = change.positionChange()
                if (!lockedVerticalUp) {
                    sumX += delta.x
                    sumY += delta.y
                    val ax = abs(sumX)
                    val ay = abs(sumY)
                    if (ax < touchSlop && ay < touchSlop) continue

                    when {
                        ay > ax * VERTICAL_DOMINANCE_RATIO && sumY < 0f -> {
                            lockedVerticalUp = true
                            change.consume()
                            if (-sumY >= OPEN_DETAILS_UPWARD_PX) {
                                opened = true
                                onSwipeUp()
                            }
                        }
                        ax > ay * VERTICAL_DOMINANCE_RATIO -> {
                            passedToHorizontal = true
                        }
                        sumY > touchSlop -> {
                            passedToHorizontal = true
                        }
                    }
                } else {
                    change.consume()
                    sumY += delta.y
                    if (sumY > touchSlop * 0.5f) {
                        sumY = 0f
                    }
                    if (-sumY >= OPEN_DETAILS_UPWARD_PX) {
                        opened = true
                        onSwipeUp()
                        break
                    }
                }
            }
        }
    }
}
