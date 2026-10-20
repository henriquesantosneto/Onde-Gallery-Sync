package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun Modifier.detailPanelVerticalDrag(
    enabled: Boolean,
    screenHeightPx: Float,
    fractionBase: () -> Float,
    onDragStart: () -> Unit = {},
    onDragFraction: (Float) -> Unit,
    onDragEnd: (velocityYPxPerSec: Float) -> Unit,
): Modifier {
    if (!enabled || screenHeightPx <= 0f) return this
    var dragging by remember { mutableStateOf(false) }
    val dragState = rememberDraggableState { deltaY ->
        if (!dragging) {
            dragging = true
            onDragStart()
        }
        val base = fractionBase()
        val gain = if (deltaY > 0f) 2.85f else 1.55f
        val next = (base - deltaY * gain / screenHeightPx)
            .coerceIn(0f, MediaDetailSheetAnchors.FULL)
        onDragFraction(next)
    }
    return draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        enabled = enabled,
        onDragStarted = {
            dragging = true
            onDragStart()
        },
        onDragStopped = { velocity ->
            dragging = false
            onDragEnd(velocity)
        },
    )
}
