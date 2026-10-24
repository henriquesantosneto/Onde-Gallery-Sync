package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

private const val IMMERSIVE_ZOOM_IDLE_SCALE = 1.05f

/**
 * Toque simples na passagem Final — não compete com pinça/pager no Main.
 * Só alterna imersivo com zoom ~1 e se nenhum filho consumiu o toque (ex.: botões).
 */
@Composable
fun Modifier.detailImmersiveTap(
    zoomTransform: ZoomTransformHolder,
    onToggleImmersive: () -> Unit,
): Modifier {
    val transformRef = rememberUpdatedState(zoomTransform)
    val onTapRef = rememberUpdatedState(onToggleImmersive)
    return pointerInput(zoomTransform, onToggleImmersive) {
        detectImmersiveToggleTapFinalPass(
            onToggle = {
                if (abs(transformRef.value.scale - 1f) < (IMMERSIVE_ZOOM_IDLE_SCALE - 1f)) {
                    onTapRef.value()
                }
            },
        )
    }
}
