package com.onegallery.sync.ui.gallery

import coil.request.ImageRequest

/**
 * Coil: na grade usamos hardware bitmap (GPU) quando possível; detalhe/zoom/filmstrip
 * mantêm software bitmap por transformações e compatibilidade.
 */
internal fun ImageRequest.Builder.applyGalleryThumbnailLoading(
    style: GalleryThumbnailStyle,
    allowHardwareBitmap: Boolean,
): ImageRequest.Builder {
    val hardware = style == GalleryThumbnailStyle.Grid && allowHardwareBitmap
    return crossfade(style == GalleryThumbnailStyle.Detail)
        .allowHardware(hardware)
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
