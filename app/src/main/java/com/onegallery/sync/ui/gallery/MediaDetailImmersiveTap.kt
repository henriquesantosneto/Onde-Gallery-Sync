package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** Toque simples na mídia alterna chrome imersivo (foto/vídeo), sem bloquear pinça ou pager. */
fun Modifier.mediaDetailToggleImmersiveOnTap(
    enabled: Boolean,
    transform: ZoomTransformHolder?,
    onToggleImmersive: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(transform, onToggleImmersive) {
        detectTapGestures(
            onTap = {
                val scale = transform?.scale ?: 1f
                if (abs(scale - 1f) < 0.06f) {
                    onToggleImmersive()
                }
            },
        )
    }
}
