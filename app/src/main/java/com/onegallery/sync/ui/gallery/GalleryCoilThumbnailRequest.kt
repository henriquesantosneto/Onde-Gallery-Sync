package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import coil.request.ImageRequest
import coil.size.Precision

/**
 * Coil fora da grade de fotos. A grade usa [galleryGridImageRequest] (RGB_565).
 * Filmstrip pode ir para a GPU; detalhe e zoom ficam em software.
 */
internal fun ImageRequest.Builder.applyGalleryThumbnailLoading(
    style: GalleryThumbnailStyle,
    allowHardwareBitmap: Boolean,
    videoDecode: Boolean = false,
): ImageRequest.Builder {
    val gpuEligible = style == GalleryThumbnailStyle.Grid ||
        style == GalleryThumbnailStyle.Filmstrip
    val hardware = gpuEligible && allowHardwareBitmap && !videoDecode
    val rgb565 = (style == GalleryThumbnailStyle.Grid ||
        style == GalleryThumbnailStyle.Filmstrip) && !videoDecode
    return crossfade(style == GalleryThumbnailStyle.Detail)
        .allowHardware(hardware)
        .allowRgb565(rgb565)
}

/**
 * A grade de fotos desenha o bitmap direto. Uma camada por célula deixa a rolagem mais pesada.
 * Filmstrip e detalhe continuam com a camada de antes.
 */
internal fun Modifier.galleryGridThumbnailLayer(style: GalleryThumbnailStyle): Modifier {
    if (style == GalleryThumbnailStyle.Grid) return this
    when (style) {
        GalleryThumbnailStyle.Filmstrip -> Unit
        else -> return this
    }
    return graphicsLayer { clip = true }
}

/**
 * Miniatura da grade: JPEG pequeno em RGB_565, já no cache de memória.
 * A rolagem só copia esse bitmap. Textura de hardware por célula ficou mais lenta.
 */
internal fun galleryGridImageRequest(
    context: Context,
    data: Any,
    sizePx: Int,
    cacheKey: String,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(data)
        .size(sizePx)
        .precision(Precision.INEXACT)
        .crossfade(false)
        .allowHardware(false)
        .allowRgb565(true)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .memoryCacheKey(cacheKey)
        .build()
}

internal fun galleryThumbnailMemoryCacheKey(
    baseKey: String,
    sizePx: Int,
    style: GalleryThumbnailStyle,
    @Suppress("UNUSED_PARAMETER") allowHardwareBitmap: Boolean,
): String {
    // Chave estável — evita recarregar ao alternar hardware/software na mesma célula.
    return "$baseKey:$sizePx:${style.name}"
}
