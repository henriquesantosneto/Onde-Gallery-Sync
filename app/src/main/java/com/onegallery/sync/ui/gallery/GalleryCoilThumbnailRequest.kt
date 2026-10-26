package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import coil.request.ImageRequest
import coil.size.Precision

/**
 * Coil: na grade usamos hardware bitmap (GPU) quando possível; detalhe/zoom/filmstrip
 * mantêm software bitmap por transformações e compatibilidade.
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
 * Célula da grade: textura de GPU. A rolagem só compõe a camada, sem redesenhar o JPEG.
 * Não copie esse bitmap para um canvas de software — isso derruba o processo.
 */
internal fun Modifier.galleryGridThumbnailLayer(style: GalleryThumbnailStyle): Modifier {
    when (style) {
        GalleryThumbnailStyle.Grid,
        GalleryThumbnailStyle.Filmstrip,
        -> Unit
        else -> return this
    }
    return graphicsLayer { clip = true }
}

/**
 * Grade de fotos: ImageDecoder aloca a miniatura na GPU.
 * Se o aparelho recusar, o fallback fica em RGB_565 na CPU.
 */
internal fun galleryGridImageRequest(
    context: Context,
    data: Any,
    sizePx: Int,
    cacheKey: String,
    useGpu: Boolean,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(data)
        .size(sizePx)
        .precision(Precision.INEXACT)
        .crossfade(false)
        .allowHardware(useGpu)
        .apply {
            if (useGpu) {
                allowRgb565(false)
            } else {
                allowRgb565(true)
                bitmapConfig(Bitmap.Config.RGB_565)
            }
        }
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
