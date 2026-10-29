package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import com.onegallery.sync.data.local.MediaKind

/** Quadrado da grade no instante do toque, já em coordenadas da janela. */
data class GridExpandOrigin(
    val canonicalPath: String,
    val kind: MediaKind,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap?,
)

internal data class ExpandRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Retângulo em que a foto cabe inteira, centrada, como no visualizador. */
internal fun fittedExpandRect(
    contentWidth: Float,
    contentHeight: Float,
    boundsWidth: Float,
    boundsHeight: Float,
): ExpandRect {
    if (contentWidth <= 0f || contentHeight <= 0f || boundsWidth <= 0f || boundsHeight <= 0f) {
        return ExpandRect(0f, 0f, boundsWidth.coerceAtLeast(0f), boundsHeight.coerceAtLeast(0f))
    }
    val scale = minOf(boundsWidth / contentWidth, boundsHeight / contentHeight)
    val width = contentWidth * scale
    val height = contentHeight * scale
    val left = (boundsWidth - width) / 2f
    val top = (boundsHeight - height) / 2f
    return ExpandRect(left, top, left + width, top + height)
}

internal fun lerpExpandRect(start: ExpandRect, end: ExpandRect, fraction: Float): ExpandRect {
    val t = fraction.coerceIn(0f, 1f)
    return ExpandRect(
        left = start.left + (end.left - start.left) * t,
        top = start.top + (end.top - start.top) * t,
        right = start.right + (end.right - start.right) * t,
        bottom = start.bottom + (end.bottom - start.bottom) * t,
    )
}
