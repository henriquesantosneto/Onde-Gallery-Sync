package com.onegallery.sync.ui.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

private const val GRID_EXPAND_MS = 200

/**
 * O quadrado visível cresce até o lugar da foto aberta.
 * A grade continua atrás no primeiro quadro, sem tela preta antes do movimento.
 */
@Composable
internal fun GridCellExpandOverlay(
    origin: GridExpandOrigin,
    revealContent: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = origin.bitmap
    if (bitmap == null || bitmap.isRecycled || origin.width <= 0 || origin.height <= 0) {
        LaunchedEffect(origin.canonicalPath) { onFinished() }
        return
    }
    val motion = remember(origin.canonicalPath) { Animatable(0f) }
    LaunchedEffect(origin.canonicalPath) {
        motion.snapTo(0f)
        motion.animateTo(
            1f,
            tween(durationMillis = GRID_EXPAND_MS, easing = FastOutSlowInEasing),
        )
    }
    val progress = motion.value
    LaunchedEffect(progress, revealContent) {
        if (progress >= 1f && revealContent) {
            withFrameNanos { }
            onFinished()
        }
    }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val end = fittedExpandRect(
                contentWidth = bitmap.width.toFloat(),
                contentHeight = bitmap.height.toFloat(),
                boundsWidth = size.width,
                boundsHeight = size.height,
            )
            val rect = lerpExpandRect(
                start = ExpandRect(
                    left = origin.left.toFloat(),
                    top = origin.top.toFloat(),
                    right = (origin.left + origin.width).toFloat(),
                    bottom = (origin.top + origin.height).toFloat(),
                ),
                end = end,
                fraction = progress,
            )
            drawRect(Color.Black.copy(alpha = progress.coerceIn(0f, 1f)))
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(
                    rect.width.roundToInt().coerceAtLeast(1),
                    rect.height.roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}
