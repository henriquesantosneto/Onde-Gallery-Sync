package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val HORIZONTAL_SWIPE_MIN_PX = 56f
private const val HORIZONTAL_DOMINANCE = 1.15f

/**
 * Faixas laterais: deslize horizontal troca mídia quando o centro (vídeo/ExoPlayer) consome toques.
 */
@Composable
fun MediaDetailEdgePagerSwipeOverlay(
    enabled: Boolean,
    onSwipeToNext: () -> Unit,
    onSwipeToPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val edgeDp = (LocalConfiguration.current.screenWidthDp * 0.14f).coerceIn(48f, 96f).dp
    val edgeModifier = Modifier
        .fillMaxHeight()
        .width(edgeDp)
        .horizontalSwipeChangePage(
            onSwipeToNext = onSwipeToNext,
            onSwipeToPrevious = onSwipeToPrevious,
        )
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = edgeModifier.align(Alignment.CenterStart))
        Box(modifier = edgeModifier.align(Alignment.CenterEnd))
    }
}

private fun Modifier.horizontalSwipeChangePage(
    onSwipeToNext: () -> Unit,
    onSwipeToPrevious: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var sumX = 0f
        var sumY = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                val ax = abs(sumX)
                val ay = abs(sumY)
                if (ax >= HORIZONTAL_SWIPE_MIN_PX && ax > ay * HORIZONTAL_DOMINANCE) {
                    if (sumX < 0f) onSwipeToNext() else onSwipeToPrevious()
                }
                break
            }
            val delta = change.positionChange()
            sumX += delta.x
            sumY += delta.y
        }
    }
}
