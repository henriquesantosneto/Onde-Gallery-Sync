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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** Fração da altura da tela ocupada pelo painel de detalhes (0 = fechado). */
object MediaDetailSheetAnchors {
    const val FULL = 0.88f
    /** Detalhes resumidos (data, arquivo, câmera, mapa). */
    const val SIMPLE = 0.45f

    private val anchors = floatArrayOf(0f, SIMPLE, FULL)

    /** Snap ao âncora mais próximo; fling vertical promove/rebaixa um degrau. */
    fun snap(fraction: Float, velocityYPxPerSec: Float = 0f): Float {
        val flingUp = velocityYPxPerSec < -900f
        val flingDown = velocityYPxPerSec > 900f
        if (flingUp) {
            val idx = anchors.indexOfLast { fraction >= it - 0.04f }.coerceAtLeast(0)
            return anchors[(idx + 1).coerceAtMost(anchors.lastIndex)]
        }
        if (flingDown) {
            val idx = anchors.indexOfFirst { fraction <= it + 0.04f }
                .let { if (it < 0) anchors.lastIndex else it }
            return anchors[(idx - 1).coerceAtLeast(0)]
        }
        return when {
            fraction < 0.12f -> 0f
            // Acima da metade entre simples e completo → nível 2 (sem exigir ~62% da tela).
            fraction >= 0.50f -> FULL
            else -> SIMPLE
        }
    }
}

enum class MediaDetailContentLevel {
    /** Resumo Samsung (sem bloco técnico longo). */
    Simple,
    /** EXIF / “Mais informações”. */
    Full,
}

fun contentLevelForPanelFraction(fraction: Float): MediaDetailContentLevel {
    return if (fraction >= 0.58f) {
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
    LaunchedEffect(dragging, dragFraction) {
        if (dragging) {
            animatable.snapTo(dragFraction)
        }
    }
    LaunchedEffect(dragging, settleTarget) {
        if (!dragging) {
            animatable.animateTo(settleTarget, tween(durationMillis = 220))
        }
    }
    return animatable.value
}

private const val VERTICAL_DOMINANCE_RATIO = 1.15f

/**
 * Arraste vertical contínuo: o painel acompanha o dedo até soltar (snap nos âncoras).
 * [fractionBaseState] é atualizado a cada frame na UI; o gesto lê [.floatValue] ao tocar
 * (evita closure obsoleta dentro do [pointerInput]).
 */
fun Modifier.draggableDetailPanelHeight(
    enabled: Boolean,
    screenHeightPx: Float,
    fractionBaseState: MutableFloatState,
    onDragStart: () -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: (velocityYPxPerSec: Float) -> Unit,
    /** 1 = área inteira; 0.2 = só a faixa inferior (ex.: abrir detalhes sem brigar com pinça). */
    activationEdgeHeightFraction: Float = 1f,
): Modifier {
    if (!enabled || screenHeightPx <= 0f) return this
    return pointerInput(enabled, screenHeightPx, activationEdgeHeightFraction) {
        val touchSlop = viewConfiguration.touchSlop
        val maxFraction = MediaDetailSheetAnchors.FULL
        val edgeFraction = activationEdgeHeightFraction.coerceIn(0.05f, 1f)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
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

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                if (event.changes.count { it.pressed } >= 2) {
                    passedToChildren = true
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
                    if (ax < touchSlop && ay < touchSlop) continue

                    when {
                        ay > ax * VERTICAL_DOMINANCE_RATIO -> {
                            lockedVertical = true
                            change.consume()
                            dragging = true
                            onDragStart()
                            val fraction = (baseFraction - sumY / screenHeightPx).coerceIn(0f, maxFraction)
                            onDragFraction(fraction)
                        }
                        ax > ay * VERTICAL_DOMINANCE_RATIO -> passedToChildren = true
                        baseFraction < 0.05f && sumY > touchSlop -> passedToChildren = true
                    }
                } else {
                    change.consume()
                    sumY += delta.y
                    val fraction = (baseFraction - sumY / screenHeightPx).coerceIn(0f, maxFraction)
                    onDragFraction(fraction)
                }
            }
            if (dragging) {
                val velocityY = velocityTracker.calculateVelocity().y
                onDragEnd(velocityY)
            }
        }
    }
}
