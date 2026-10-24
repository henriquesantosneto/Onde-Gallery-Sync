package com.onegallery.sync.ui.gallery

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import coil.request.ImageRequest

/**
 * Coil: na grade usamos hardware bitmap (GPU) quando possível; detalhe/zoom/filmstrip
 * mantêm software bitmap por transformações e compatibilidade.
 */
internal fun ImageRequest.Builder.applyGalleryThumbnailLoading(
    style: GalleryThumbnailStyle,
    allowHardwareBitmap: Boolean,
): ImageRequest.Builder {
    val grid = style == GalleryThumbnailStyle.Grid
    val hardware = grid && allowHardwareBitmap
    return crossfade(style == GalleryThumbnailStyle.Detail)
        .allowHardware(hardware)
        .allowRgb565(grid)
}

/** Camada de composição estável por célula — rolagem desenha a textura, não re-layout. */
internal fun Modifier.galleryGridThumbnailLayer(style: GalleryThumbnailStyle): Modifier {
    if (style != GalleryThumbnailStyle.Grid) return this
    return graphicsLayer { clip = true }
}

internal fun galleryThumbnailMemoryCacheKey(
    baseKey: String,
    sizePx: Int,
    style: GalleryThumbnailStyle,
    allowHardwareBitmap: Boolean,
): String {
    val hw = style == GalleryThumbnailStyle.Grid && allowHardwareBitmap
    return "$baseKey:$sizePx:${style.name}:hw=$hw"
}
