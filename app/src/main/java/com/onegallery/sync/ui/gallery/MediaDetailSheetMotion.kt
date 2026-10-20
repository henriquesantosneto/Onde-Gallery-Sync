package com.onegallery.sync.ui.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

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
