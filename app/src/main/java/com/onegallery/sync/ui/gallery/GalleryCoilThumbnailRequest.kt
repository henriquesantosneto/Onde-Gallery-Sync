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
    val gpuEligible = style == GalleryThumbnailStyle.Grid ||
        style == GalleryThumbnailStyle.Filmstrip
    val hardware = gpuEligible && allowHardwareBitmap
    val rgb565 = style == GalleryThumbnailStyle.Grid ||
        style == GalleryThumbnailStyle.Filmstrip
    return crossfade(style == GalleryThumbnailStyle.Detail)
        .allowHardware(hardware)
        .allowRgb565(rgb565)
}

/** Camada de composição estável por célula — rolagem desenha a textura, não re-layout. */
internal fun Modifier.galleryGridThumbnailLayer(style: GalleryThumbnailStyle): Modifier {
    when (style) {
        GalleryThumbnailStyle.Grid,
        GalleryThumbnailStyle.Filmstrip,
        -> Unit
        else -> return this
    }
    return graphicsLayer { clip = true }
}

internal fun galleryThumbnailMemoryCacheKey(
    baseKey: String,
    sizePx: Int,
    style: GalleryThumbnailStyle,
    allowHardwareBitmap: Boolean,
): String {
    val gpuEligible = style == GalleryThumbnailStyle.Grid ||
        style == GalleryThumbnailStyle.Filmstrip
    val hw = gpuEligible && allowHardwareBitmap
    return "$baseKey:$sizePx:${style.name}:hw=$hw"
}
