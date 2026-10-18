package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun ZoomableMediaContainer(
    resetKey: Any?,
    modifier: Modifier = Modifier,
    interceptSingleTap: Boolean = true,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }

    fun notifyZoom() {
        onZoomActiveChanged(scale > 1.05f)
    }

    fun toggleDoubleTapZoom() {
        if (scale > 1.05f) {
            scale = 1f
            offset = Offset.Zero
        } else {
            scale = 2.5f
            offset = Offset.Zero
        }
        notifyZoom()
    }

    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            content()
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(resetKey, scale) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale > 1f) offset + pan else Offset.Zero
                        notifyZoom()
                    }
                }
                .pointerInput(resetKey, interceptSingleTap) {
                    if (interceptSingleTap) {
                        detectTapGestures(
                            onDoubleTap = { toggleDoubleTapZoom() },
                            onTap = { onSingleTap() },
                        )
                    } else {
                        detectTapGestures(
                            onDoubleTap = { toggleDoubleTapZoom() },
                        )
                    }
                },
        )
    }
}
