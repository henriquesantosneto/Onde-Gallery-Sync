package com.onegallery.sync.ui.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** Fração da altura da tela ocupada pelo painel de detalhes (0 = fechado). */
object MediaDetailSheetAnchors {
    const val FULL = 0.88f
    const val PEEK = 0.22f

    fun snap(fraction: Float): Float {
        return when {
            fraction < 0.08f -> 0f
            fraction < 0.34f -> PEEK
            fraction < 0.62f -> 0.52f
            else -> FULL
        }
    }
}

enum class MediaDetailContentLevel {
    /** Só data/título. */
    Peek,
    /** EXIF resumido, mapa, caminho — sem bloco técnico. */
    Standard,
    /** Tudo, incluindo linhas de “Mais informações”. */
    Full,
}

fun contentLevelForPanelFraction(fraction: Float): MediaDetailContentLevel {
    return when {
        fraction >= 0.68f -> MediaDetailContentLevel.Full
        fraction >= 0.30f -> MediaDetailContentLevel.Standard
        fraction > 0.04f -> MediaDetailContentLevel.Peek
        else -> MediaDetailContentLevel.Peek
    }
}

@Composable
fun rememberMediaDetailSheetFraction(
    settleTarget: Float,
    dragFraction: Float,
    dragging: Boolean,
): Float {
    val animated by animateFloatAsState(
        targetValue = settleTarget,
        animationSpec = tween(durationMillis = 220),
        label = "detailsSettleFraction",
    )
    return if (dragging) dragFraction else animated
}

private const val VERTICAL_DOMINANCE_RATIO = 1.35f

/**
 * Deslize para cima na foto: o painel sobe contínuo com o dedo (não salto fixo).
 */
fun Modifier.draggableDetailsFromPhoto(
    enabled: Boolean,
    startFraction: Float,
    screenHeightPx: Float,
    onDragStart: () -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier {
    if (!enabled || screenHeightPx <= 0f) return this
    return pointerInput(enabled, startFraction, screenHeightPx) {
        val touchSlop = viewConfiguration.touchSlop
        val maxFraction = MediaDetailSheetAnchors.FULL
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var sumX = 0f
            var sumY = 0f
            var lockedVerticalUp = false
            var passedToHorizontal = false
            var dragging = false
            var baseFraction = startFraction.coerceIn(0f, maxFraction)

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (passedToHorizontal) break

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
                            dragging = true
                            baseFraction = startFraction.coerceIn(0f, maxFraction)
                            onDragStart()
                            val fraction = (baseFraction - sumY / screenHeightPx).coerceIn(0f, maxFraction)
                            onDragFraction(fraction)
                        }
                        ay > ax * VERTICAL_DOMINANCE_RATIO && sumY > 0f && startFraction > 0.05f -> {
                            lockedVerticalUp = true
                            change.consume()
                            dragging = true
                            baseFraction = startFraction
                            onDragStart()
                            val fraction = (baseFraction - sumY / screenHeightPx).coerceIn(0f, maxFraction)
                            onDragFraction(fraction)
                        }
                        ax > ay * VERTICAL_DOMINANCE_RATIO -> passedToHorizontal = true
                        sumY > touchSlop && startFraction < 0.05f -> passedToHorizontal = true
                    }
                } else {
                    change.consume()
                    sumY += delta.y
                    val fraction = (baseFraction - sumY / screenHeightPx).coerceIn(0f, maxFraction)
                    onDragFraction(fraction)
                }
            }
            if (dragging) {
                onDragEnd()
            }
        }
    }
}
