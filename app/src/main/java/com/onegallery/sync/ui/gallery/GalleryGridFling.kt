package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class BoostedGridFlingBehavior(
    private val base: FlingBehavior,
    private val velocityMultiplier: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        return with(base) { performFling(initialVelocity * velocityMultiplier) }
    }
}

/** Fling mais longo e suave, próximo à sensação da Galeria Samsung. */
@Composable
fun rememberGalleryGridFlingBehavior(): FlingBehavior {
    val base = ScrollableDefaults.flingBehavior()
    return remember(base) {
        BoostedGridFlingBehavior(base, velocityMultiplier = 1.28f)
    }
}
