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
    /** Detalhes resumidos (data, arquivo, câmera, mapa). */
    const val SIMPLE = 0.45f

    fun snap(fraction: Float): Float {
        return when {
            fraction < 0.10f -> 0f
            fraction < 0.62f -> SIMPLE
            else -> FULL
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
    val animated by animateFloatAsState(
        targetValue = settleTarget,
        animationSpec = tween(durationMillis = 220),
        label = "detailsSettleFraction",
    )
    return if (dragging) dragFraction else animated
}

private const val VERTICAL_DOMINANCE_RATIO = 1.15f

/**
 * Arraste vertical contínuo: o painel acompanha o dedo até soltar (snap nos âncoras).
 * [fractionAtDragStart] é lido só no início do gesto — não entra na chave do [pointerInput].
 */
fun Modifier.draggableDetailPanelHeight(
    enabled: Boolean,
    screenHeightPx: Float,
    fractionAtDragStart: () -> Float,
    onDragStart: () -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier {
    if (!enabled || screenHeightPx <= 0f) return this
    return pointerInput(enabled, screenHeightPx) {
        val touchSlop = viewConfiguration.touchSlop
        val maxFraction = MediaDetailSheetAnchors.FULL
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var sumX = 0f
            var sumY = 0f
            var lockedVertical = false
            var passedToChildren = false
            var dragging = false
            var baseFraction = 0f

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (passedToChildren) break

                val delta = change.positionChange()
                if (!lockedVertical) {
                    sumX += delta.x
                    sumY += delta.y
                    val ax = abs(sumX)
                    val ay = abs(sumY)
                    if (ax < touchSlop && ay < touchSlop) continue

                    baseFraction = fractionAtDragStart().coerceIn(0f, maxFraction)
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
                onDragEnd()
            }
        }
    }
}

/** @see draggableDetailPanelHeight */
fun Modifier.draggableDetailsFromPhoto(
    enabled: Boolean,
    startFraction: Float,
    screenHeightPx: Float,
    onDragStart: () -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = draggableDetailPanelHeight(
    enabled = enabled,
    screenHeightPx = screenHeightPx,
    fractionAtDragStart = { startFraction },
    onDragStart = onDragStart,
    onDragFraction = onDragFraction,
    onDragEnd = onDragEnd,
)
