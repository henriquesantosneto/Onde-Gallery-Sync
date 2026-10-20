package com.onegallery.sync.ui.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

object MediaDetailSheetAnchors {
    const val FULL = 0.88f
    const val SIMPLE = 0.45f

    private val anchors = floatArrayOf(0f, SIMPLE, FULL)

    fun snap(fraction: Float, velocityYPxPerSec: Float = 0f): Float {
        val flingUp = velocityYPxPerSec < -780f
        val flingDown = velocityYPxPerSec > 480f
        if (flingUp) {
            val idx = anchors.indexOfLast { fraction >= it - 0.04f }.coerceAtLeast(0)
            val next = (idx + 1).coerceAtMost(anchors.lastIndex)
            if (fraction < SIMPLE * 0.55f && next > 1) return anchors[1]
            return anchors[next]
        }
        if (flingDown) {
            val idx = anchors.indexOfFirst { fraction <= it + 0.04f }
                .let { if (it < 0) anchors.lastIndex else it }
            return anchors[(idx - 1).coerceAtLeast(0)]
        }
        val closeBias = when {
            velocityYPxPerSec > 160f -> 0.08f
            velocityYPxPerSec < -160f -> -0.04f
            else -> 0f
        }
        val f = (fraction - closeBias).coerceIn(0f, 1f)
        return when {
            f < 0.22f -> 0f
            f < 0.64f -> SIMPLE
            else -> FULL
        }
    }
}

enum class MediaDetailContentLevel {
    Simple,
    Full,
}

fun contentLevelForPanelFraction(fraction: Float): MediaDetailContentLevel {
    return if (fraction >= 0.64f) {
        MediaDetailContentLevel.Full
    } else {
        MediaDetailContentLevel.Simple
    }
}

@Composable
fun rememberMediaDetailSheetFraction(
    settleTarget: Float,
    dragFraction: Float,
    dragging: Boolean,
): Float {
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(dragging, settleTarget) {
        if (!dragging) {
            animatable.animateTo(settleTarget, tween(durationMillis = 200))
        }
    }
    return if (dragging) dragFraction else animatable.value
}

private const val DETAIL_PANEL_DRAG_GAIN_OPEN = 1.55f
private const val DETAIL_PANEL_DRAG_GAIN_CLOSE = 2.85f
private const val HORIZONTAL_PASS_RATIO = 1.04f
private const val VERTICAL_OPEN_RATIO_CLOSED = 1.32f
private const val VERTICAL_DOMINANCE_RATIO = 1.38f

private fun panelFractionFromDrag(
    baseFraction: Float,
    sumY: Float,
    screenHeightPx: Float,
    maxFraction: Float,
): Float {
    val gain = if (sumY > 0f) DETAIL_PANEL_DRAG_GAIN_CLOSE else DETAIL_PANEL_DRAG_GAIN_OPEN
    return (baseFraction - sumY * gain / screenHeightPx)
        .coerceIn(0f, maxFraction)
}

/**
 * Passagem **Initial** no ancestral da foto/pager: prioriza horizontal → pager/carrossel;
 * 2+ dedos → pinça; vertical dominante → altura do painel (nível 0/1/2).
 */
fun Modifier.draggableDetailPanelHeight(
    enabled: Boolean,
    screenHeightPx: Float,
    fractionBaseState: MutableFloatState,
    onDragStart: () -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: (velocityYPxPerSec: Float) -> Unit,
    activationEdgeHeightFraction: Float = 1f,
): Modifier {
    if (!enabled || screenHeightPx <= 0f) return this
    return pointerInput(enabled, screenHeightPx, activationEdgeHeightFraction) {
        val touchSlop = viewConfiguration.touchSlop
        val maxFraction = MediaDetailSheetAnchors.FULL
        val edgeFraction = activationEdgeHeightFraction.coerceIn(0.05f, 1f)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (edgeFraction < 1f) {
                val minY = size.height * (1f - edgeFraction)
                if (down.position.y < minY) {
                    return@awaitEachGesture
                }
            }
            var sumX = 0f
            var sumY = 0f
            var lockedVertical = false
            var passedToChildren = false
            var dragging = false
            var baseFraction = fractionBaseState.floatValue.coerceIn(0f, maxFraction)
            val velocityTracker = VelocityTracker()
            val panelClosed = baseFraction < 0.05f

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (passedToChildren) break

                val delta = change.positionChange()
                if (delta != Offset.Zero) {
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                }
                if (!lockedVertical) {
                    sumX += delta.x
                    sumY += delta.y
                    val ax = abs(sumX)
                    val ay = abs(sumY)
                    val slop = touchSlop * when {
                        panelClosed -> 1.75f
                        baseFraction > 0.03f -> 0.45f
                        else -> 1f
                    }
                    if (ax < slop && ay < slop) continue

                    when {
                        ax > ay * HORIZONTAL_PASS_RATIO -> passedToChildren = true
                        panelClosed && sumY > touchSlop -> passedToChildren = true
                        panelClosed && sumY < -slop * 0.35f && ay > ax * VERTICAL_OPEN_RATIO_CLOSED -> {
                            lockedVertical = true
                            change.consume()
                            dragging = true
                            onDragStart()
                            onDragFraction(
                                panelFractionFromDrag(baseFraction, sumY, screenHeightPx, maxFraction),
                            )
                        }
                        !panelClosed && baseFraction > 0.03f && ay > slop && ay >= ax -> {
                            lockedVertical = true
                            change.consume()
                            dragging = true
                            onDragStart()
                            onDragFraction(
                                panelFractionFromDrag(baseFraction, sumY, screenHeightPx, maxFraction),
                            )
                        }
                        !panelClosed && ay > ax * VERTICAL_DOMINANCE_RATIO -> {
                            lockedVertical = true
                            change.consume()
                            dragging = true
                            onDragStart()
                            onDragFraction(
                                panelFractionFromDrag(baseFraction, sumY, screenHeightPx, maxFraction),
                            )
                        }
                    }
                } else {
                    change.consume()
                    sumY += delta.y
                    onDragFraction(
                        panelFractionFromDrag(baseFraction, sumY, screenHeightPx, maxFraction),
                    )
                }
            }
            if (dragging) {
                onDragEnd(velocityTracker.calculateVelocity().y)
            }
        }
    }
}
