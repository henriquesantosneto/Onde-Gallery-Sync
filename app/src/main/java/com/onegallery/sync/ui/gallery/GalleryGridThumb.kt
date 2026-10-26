package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.onegallery.sync.OneGalleryApplication

/**
 * Grade: só o JPEG já gravado, ou a foto que está no aparelho.
 * Não lê o arquivo inteiro nem abre vídeo enquanto a lista rola.
 * A célula não observa o sincronismo: a miniatura nova entra quando a lista troca.
 */
@Composable
fun GalleryGridThumb(
    item: GalleryDisplayItem,
    contentDescription: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCache = remember(app) { app.mediaCacheRepository }
    val thumbPath = item.cachedPlaceholderThumb?.absolutePath
    val localKey = item.localUri?.toString()
    val thumbFile = remember(item.canonicalPath, item.oneDriveItemId, thumbPath, localKey) {
        gridPaintFile(item, mediaCache)
    }
    val localPhotoUri = if (thumbFile == null && item.isLocalPhotoOnDevice()) item.localUri else null
    val paintModel = thumbFile?.let(::DiskThumb) ?: localPhotoUri ?: return
    val cacheStamp = thumbFile?.lastModified() ?: 0L
    val request = remember(paintModel, sizePx, item.canonicalPath, cacheStamp, thumbPath, localKey) {
        galleryGridImageRequest(
            context = context,
            data = paintModel,
            sizePx = sizePx,
            cacheKey = "${item.canonicalPath}:grid:$sizePx:$cacheStamp:$thumbPath",
        )
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
